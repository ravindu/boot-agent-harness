package com.tw.docsagent.tool;

import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Semantic search over the ingested Spring Boot reference docs.
 *
 * <p>Each hit is wrapped in a {@code <doc id="S3" ...>} element. The id is what Claude cites; the
 * wrapper marks the text as retrieved data, so instructions that happen to appear in a doc are
 * content to report on, not commands to follow ("retrieved content is untrusted").
 */
@Component
@RequiredArgsConstructor
public class SearchDocsTool implements AgentTool {

    static final int DEFAULT_TOP_K = 5;
    static final int MAX_TOP_K = 8;

    private final VectorStore vectorStore;
    private final DocsSourceProperties docsProperties;

    @Override
    public String name() {
        return "searchDocs";
    }

    @Override
    public String description() {
        return """
                Searches the official Spring Boot %s reference documentation (Testing and Data sections) \
                and returns the most relevant passages, each labelled with a source id like S1. \
                Call this before answering any question about Spring Boot behaviour, configuration, \
                annotations or APIs. Call it again with a rephrased or narrower query if the passages \
                don't answer the question. Phrase the query as the topic you need, e.g. \
                "@DataJpaTest transaction rollback", not as a full conversation."""
                .formatted(docsProperties.bootVersion());
    }

    @Override
    public Map<String, Object> inputProperties() {
        return Map.of(
                "query", Map.of(
                        "type", "string",
                        "description", "What to look for in the docs."),
                "topK", Map.of(
                        "type", "integer",
                        "description", "Number of passages to return, 1 to " + MAX_TOP_K + ". Use "
                                + DEFAULT_TOP_K + " unless you need more or fewer."));
    }

    @Override
    public List<String> requiredInputs() {
        return List.of("query", "topK");
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext context) {
        String query = input.get("query") instanceof String s ? s.strip() : "";
        if (query.isEmpty()) {
            return ToolResult.error("query must not be empty.");
        }
        int topK = input.get("topK") instanceof Number n ? n.intValue() : DEFAULT_TOP_K;
        topK = Math.clamp(topK, 1, MAX_TOP_K); // strict tool schemas can't express minimum/maximum

        FilterExpressionBuilder f = new FilterExpressionBuilder();
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(f.eq("bootVersion", docsProperties.bootVersion()).build())
                .build());

        if (hits.isEmpty()) {
            return ToolResult.ok("No matching passages found for \"" + query + "\". Try different terms.");
        }
        StringBuilder out = new StringBuilder("<search_results>\n");
        for (Document hit : hits) {
            Map<String, Object> meta = hit.getMetadata();
            String title = String.valueOf(meta.getOrDefault("breadcrumb", meta.get("title")));
            String url = String.valueOf(meta.get("url"));
            SourceCatalog.Source source = context.sources().register(hit.getId(), title, url);
            out.append("<doc id=\"").append(source.label())
                    .append("\" title=\"").append(attribute(title))
                    .append("\" url=\"").append(attribute(url)).append("\">\n")
                    .append(body(hit.getText()))
                    .append("\n</doc>\n");
        }
        return ToolResult.ok(out.append("</search_results>").toString());
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /** A doc must not be able to close its own wrapper and pose as harness text. */
    private static String body(String text) {
        return text == null ? "" : text.replace("</doc", "&lt;/doc").replace("<doc", "&lt;doc")
                .replace("</search_results", "&lt;/search_results");
    }
}
