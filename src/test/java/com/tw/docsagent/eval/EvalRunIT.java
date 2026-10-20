package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.agent.AgentLoop;
import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.CostCalculator;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.eval.AnswerJudge.Judgement;
import com.tw.docsagent.llm.LlmGateway;
import com.tw.docsagent.retrieval.ingest.IngestionProperties;
import com.tw.docsagent.retrieval.ingest.IngestionService;
import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs the golden set through the real agent and writes a scorecard to {@code evals/reports/}.
 *
 * <pre>
 * ./mvnw verify -Plive -Dit.test=EvalRunIT -Deval.label=baseline          # all questions (~$0.60, a few minutes)
 * ./mvnw verify -Plive -Dit.test=EvalRunIT -Deval.only=id-one,id-two      # a subset, while iterating
 * </pre>
 *
 * <p>For each question: one agent run (traced like any other run), then the Haiku judge on the
 * answer, then {@link EvalScorer}. Ingests the configured docs into a fresh Testcontainers database
 * first, so every run starts from the same data.
 *
 * <p>The test doesn't fail on a low score: an eval measures, it doesn't gate. It fails only when the
 * run itself is broken (golden set refers to sections that weren't ingested, every run FAILED).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EvalRunIT {

    @Autowired
    IngestionService ingestionService;

    @Autowired
    AgentLoop agentLoop;

    @Autowired
    LlmGateway llmGateway;

    @Autowired
    CostCalculator costCalculator;

    @Autowired
    AnthropicProperties anthropic;

    @Autowired
    IngestionProperties ingestion;

    @Autowired
    DocsSourceProperties docs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Test
    void runGoldenSet() {
        List<GoldenQuestion> questions = selected(GoldenSet.load(), System.getProperty("eval.only", ""));
        String label = System.getProperty("eval.label", "run");
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        ingestionService.ingestAll();
        assertExpectedSectionsWereIngested(questions);

        AnswerJudge judge = new AnswerJudge(llmGateway, anthropic.helperModel(), costCalculator);
        List<QuestionResult> results = new ArrayList<>();
        for (GoldenQuestion question : questions) {
            long start = System.nanoTime();
            AgentRunResult run = agentLoop.run(question.question());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            Judgement judgement = null;
            String judgeError = null;
            if (run.status() == RunStatus.COMPLETED) {
                try {
                    judgement = judge.judge(question, run.answer());
                } catch (RuntimeException e) {
                    judgeError = e.getMessage();
                }
            }
            QuestionResult result = EvalScorer.score(question, run, latencyMs, judgement, judgeError);
            results.add(result);
            System.out.printf("[%d/%d] %s %s retrieval=%s keyPoints=%d/%d cost=$%s%n", results.size(), questions.size(),
                    result.passed() ? "PASS" : "FAIL", result.id(), result.retrievalHit(),
                    result.keyPointsMet(), result.keyPointsTotal(), result.agentCostUsd().add(result.judgeCostUsd()));
        }

        EvalReport report = new EvalReport(label, startedAt.toString(), config(), results);
        Path written = report.write(Path.of("evals", "reports"));
        System.out.println(report.toMarkdown());
        System.out.println("Report written to " + written.toAbsolutePath());

        assertThat(results).as("every agent run failed; check the API key, model or network")
                .anyMatch(r -> r.status() != RunStatus.FAILED);
    }

    /** A typo'd anchor would silently count as a retrieval miss on every run; fail before spending money. */
    private void assertExpectedSectionsWereIngested(List<GoldenQuestion> questions) {
        Set<String> ingested = Set.copyOf(jdbc.sql("SELECT DISTINCT metadata->>'anchor' FROM vector_store")
                .query(String.class).list());
        List<String> unknown = questions.stream()
                .flatMap(q -> q.expectedSources().stream().filter(a -> !ingested.contains(a)).map(a -> q.id() + ": " + a))
                .toList();
        assertThat(unknown).as("expectedSources anchors that are not in the ingested docs").isEmpty();
    }

    private Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("agent", anthropic.agentModel() + " (effort " + anthropic.agentEffort() + ")");
        config.put("judge", anthropic.helperModel());
        config.put("docs", "Boot " + docs.bootVersion() + ", " + docs.pages().size() + " pages");
        config.put("maxWordsPerChunk", String.valueOf(ingestion.maxWordsPerChunk()));
        return config;
    }

    static List<GoldenQuestion> selected(List<GoldenQuestion> all, String only) {
        if (only.isBlank()) {
            return all;
        }
        Set<String> ids = Set.copyOf(Arrays.stream(only.split(",")).map(String::strip).toList());
        List<GoldenQuestion> chosen = all.stream().filter(q -> ids.contains(q.id())).toList();
        assertThat(chosen).as("questions matching -Deval.only=" + only).hasSize(ids.size());
        return chosen;
    }
}
