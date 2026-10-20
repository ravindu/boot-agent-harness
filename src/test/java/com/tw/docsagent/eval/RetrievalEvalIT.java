package com.tw.docsagent.eval;

import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.retrieval.ingest.IngestionProperties;
import com.tw.docsagent.retrieval.ingest.IngestionService;
import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval eval: measures search on its own, without Claude. For every answer question it
 * searches the vector store the way {@code searchDocs} does and records the rank of the chunk
 * holding the answer ({@code expectedText}).
 *
 * <pre>
 * ./mvnw verify -Dit.test=RetrievalEvalIT -Deval.retrieval=true -Deval.label=retrieval-baseline
 * </pre>
 *
 * <p>Why separate from {@link EvalRunIT}: in the agent eval, Claude words its search queries
 * differently every run, so one retrieval change can't be told apart from that noise. Here the
 * queries are fixed, and embeddings are deterministic: the same data and settings give the same
 * ranks. Free (no API key, no model calls); it needs network access because it ingests the real
 * docs from GitHub, which is why it only runs with {@code -Deval.retrieval=true}.
 *
 * <p>Two fixed query sets:
 * <ul>
 *   <li>the golden question text: a user's wording, the hardest case for search;</li>
 *   <li>Claude's real searchDocs queries, replayed from an agent eval report
 *       ({@code -Deval.queriesFrom=evals/reports/….json}, default: the newest report that has them).</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "eval.retrieval", matches = "true")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RetrievalEvalIT {

    private static final Path REPORTS = Path.of("evals", "reports");

    @Autowired
    IngestionService ingestionService;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DocsSourceProperties docs;

    @Autowired
    IngestionProperties ingestion;

    @Autowired
    Clock clock;

    @Test
    void measureRetrieval() {
        List<GoldenQuestion> questions = GoldenSet.load().stream()
                .filter(q -> q.expect() == Expect.ANSWER && !q.expectedText().isEmpty())
                .toList();
        String label = System.getProperty("eval.label", "retrieval");
        String startedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();

        ingestionService.ingestAll();
        GoldenSetChecks.assertEachExpectedTextIsInExactlyOneChunk(questions, GoldenSetChecks.chunkTexts(jdbc));

        Optional<Path> queriesFrom = Optional.ofNullable(System.getProperty("eval.queriesFrom")).map(Path::of)
                .or(RetrievalEvalIT::newestAgentReport);
        Map<String, List<String>> agentQueries = queriesFrom.map(RetrievalEvalIT::agentQueries).orElse(Map.of());

        List<RetrievalReport.Result> results = questions.stream().map(q -> {
            List<Document> byQuestion = search(q.question());
            int questionRank = RetrievalScorer.questionRank(q.expectedText(), List.of(texts(byQuestion)));

            List<String> queries = agentQueries.getOrDefault(q.id(), List.of());
            Integer agentRank = queries.isEmpty() ? null : RetrievalScorer.questionRank(q.expectedText(),
                    queries.stream().map(query -> texts(search(query))).toList());

            List<String> top5 = byQuestion.stream().limit(5)
                    .map(d -> d.getMetadata().get("anchor") + "#" + d.getMetadata().get("chunkIndex"))
                    .toList();
            System.out.printf("%-36s question rank=%s agent rank=%s%n", q.id(), questionRank, agentRank);
            return new RetrievalReport.Result(q.id(), q.expectedText(), questionRank, queries, agentRank, top5);
        }).toList();

        RetrievalReport report = new RetrievalReport(label, startedAt, config(),
                queriesFrom.map(p -> p.getFileName().toString()).orElse(null), results);
        Path written = report.write(REPORTS);
        System.out.println(report.toMarkdown());
        System.out.println("Report written to " + written.toAbsolutePath());
    }

    /** The same request searchDocs makes, only deeper, so a miss shows how far off it is. */
    private List<Document> search(String query) {
        FilterExpressionBuilder f = new FilterExpressionBuilder();
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(RetrievalScorer.DEPTH)
                .filterExpression(f.eq("bootVersion", docs.bootVersion()).build())
                .build());
    }

    private static List<String> texts(List<Document> documents) {
        return documents.stream().map(Document::getText).toList();
    }

    private Map<String, String> config() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("embedding", "all-MiniLM-L6-v2 (local ONNX)");
        config.put("docs", "Boot " + docs.bootVersion() + ", " + docs.pages().size() + " pages");
        config.put("maxWordsPerChunk", String.valueOf(ingestion.maxWordsPerChunk()));
        return config;
    }

    /** Newest agent eval report (has {@code searchQueries}); retrieval reports don't. */
    private static Optional<Path> newestAgentReport() {
        if (!Files.isDirectory(REPORTS)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(REPORTS)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .filter(p -> !agentQueries(p).isEmpty())
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** id → searchDocs queries, from an agent eval report's JSON. */
    private static Map<String, List<String>> agentQueries(Path report) {
        Map<String, List<String>> queries = new HashMap<>();
        JsonNode root = JsonMapper.builder().build().readTree(report.toFile());
        for (JsonNode result : root.path("results")) {
            List<String> list = result.path("searchQueries").valueStream().map(JsonNode::asString).toList();
            if (!list.isEmpty()) {
                queries.put(result.path("id").asString(), list);
            }
        }
        return queries;
    }
}
