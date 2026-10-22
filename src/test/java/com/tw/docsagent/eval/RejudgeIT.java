package com.tw.docsagent.eval;

import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.agent.CostCalculator;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.llm.LlmGateway;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Judges the answers stored in earlier agent eval reports again, without running the agent: after a
 * judge fix, or after correcting key points in the golden set. Costs cents (only the Haiku judge runs).
 *
 * <pre>
 * ./mvnw verify -Plive -Dit.test=RejudgeIT -Deval.rejudge=evals/reports/A.json,evals/reports/B.json
 * </pre>
 *
 * <p>Writes {@code <same startedAt>-<label>-rejudged.md/.json} next to each original. Only the
 * checklist, the pass decision and the note change; retrieval, agent cost and the answer are copied.
 * Results are matched by id against the current golden set; an id that is no longer in it is kept
 * unchanged.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "eval.rejudge", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RejudgeIT {

    @Autowired
    LlmGateway llmGateway;

    @Autowired
    CostCalculator costCalculator;

    @Autowired
    AnthropicProperties anthropic;

    @Autowired
    Clock clock;

    @Test
    void judgeStoredAnswersAgain() {
        Map<String, GoldenQuestion> golden = GoldenSet.load().stream()
                .collect(Collectors.toMap(GoldenQuestion::id, Function.identity()));
        AnswerJudge judge = new AnswerJudge(llmGateway, anthropic.helperModel(), costCalculator);

        for (Path path : Arrays.stream(System.getProperty("eval.rejudge").split(",")).map(s -> Path.of(s.strip())).toList()) {
            EvalReport original = EvalReport.read(path);
            List<QuestionResult> results = original.results().stream()
                    .map(stored -> rejudge(stored, golden.get(stored.id()), judge))
                    .toList();

            Map<String, String> config = new LinkedHashMap<>(original.config());
            config.put("rejudged", clock.instant().truncatedTo(ChronoUnit.SECONDS) + " with " + anthropic.helperModel());
            EvalReport report = new EvalReport(original.label() + "-rejudged", original.startedAt(), config, results);
            Path written = report.write(path.getParent());

            System.out.println("Re-judged " + path.getFileName() + ":");
            for (int i = 0; i < results.size(); i++) {
                QuestionResult before = original.results().get(i);
                QuestionResult after = results.get(i);
                if (before.passed() != after.passed()) {
                    System.out.printf("  %s %s -> %s%n", after.id(), verdict(before), verdict(after));
                }
            }
            System.out.println(report.toMarkdown());
            System.out.println("Report written to " + written.toAbsolutePath());
        }
    }

    /** The agent's stored answer, judged with the current judge and the current golden set entry. */
    private static QuestionResult rejudge(QuestionResult stored, GoldenQuestion question, AnswerJudge judge) {
        if (question == null) {
            return stored;
        }
        if (stored.status() != RunStatus.COMPLETED) {
            return EvalScorer.judged(stored, question, null, null);
        }
        try {
            return EvalScorer.judged(stored, question, judge.judge(question, stored.answer()), null);
        } catch (RuntimeException e) {
            return EvalScorer.judged(stored, question, null, e.getMessage());
        }
    }

    private static String verdict(QuestionResult result) {
        return result.passed() ? "PASS" : "FAIL";
    }
}
