package com.tw.docsagent.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scorecard of one retrieval eval: for each answer question, the rank of the chunk(s) holding the
 * answer, for two fixed query sets. Deterministic: the same data and settings give the same ranks.
 *
 * @param queriesFrom agent eval report whose searchDocs queries were replayed, or null
 */
public record RetrievalReport(String label, String startedAt, Map<String, String> config, String queriesFrom,
                              List<Result> results) {

    /**
     * @param questionRank rank when searching with the golden question text (0 = not in the top {@value RetrievalScorer#DEPTH})
     * @param agentRank    rank over Claude's replayed queries; null when the report had none for this question
     * @param topForQuestion the top 5 chunks for the question text, as {@code anchor#chunkIndex}, to see what beat a miss
     */
    public record Result(String id, List<String> expectedText, int questionRank, List<String> agentQueries,
                         Integer agentRank, List<String> topForQuestion) {
    }

    public String toMarkdown() {
        StringBuilder md = new StringBuilder("# Retrieval eval: ").append(label).append("\n\n");
        md.append(startedAt).append(" · ").append(results.size()).append(" answer questions");
        config.forEach((key, value) -> md.append(" · ").append(key).append(": `").append(value).append('`'));
        md.append("\n\nRank = position of the chunk containing the question's `expectedText` in the search results ")
                .append("(searchDocs shows Claude the top 5 by default, at most 8). No model calls: the same data gives the same ranks.\n");

        md.append("\n## Scorecard\n\n| Query set | hit@5 | hit@8 | MRR |\n|---|---|---|---|\n");
        row(md, "Golden question text", results, Result::questionRank);
        List<Result> withAgent = results.stream().filter(r -> r.agentRank() != null).toList();
        if (!withAgent.isEmpty()) {
            row(md, "Claude's queries, replayed from `" + queriesFrom + "`", withAgent, Result::agentRank);
        }

        List<Result> misses = results.stream().filter(r -> !RetrievalScorer.hitAt(r.questionRank(), 5)).toList();
        md.append("\n## Not in the top 5 for the question text (").append(misses.size()).append(")\n");
        for (Result r : misses) {
            md.append("\n### ").append(r.id()).append(": rank ").append(rank(r.questionRank())).append("\n\n");
            md.append("- expected: ").append(r.expectedText()).append('\n');
            md.append("- top 5 instead: ").append(r.topForQuestion()).append('\n');
            if (r.agentRank() != null) {
                md.append("- with Claude's queries ").append(r.agentQueries()).append(": rank ")
                        .append(rank(r.agentRank())).append('\n');
            }
        }

        md.append("\n## All questions\n\n| Question | Rank (question text) | Rank (Claude's queries) |\n|---|---|---|\n");
        for (Result r : results) {
            md.append("| ").append(r.id())
                    .append(" | ").append(rank(r.questionRank()))
                    .append(" | ").append(r.agentRank() == null ? "–" : rank(r.agentRank()))
                    .append(" |\n");
        }
        return md.toString();
    }

    /** Writes {@code <startedAt>-<label>.md} and {@code .json}; returns the Markdown path. */
    public Path write(Path dir) {
        String base = startedAt.replace(":", "").replaceAll("\\..*", "") + "-" + label;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(base + ".md"), toMarkdown());
            Files.writeString(dir.resolve(base + ".json"),
                    JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(this));
            return dir.resolve(base + ".md");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void row(StringBuilder md, String querySet, List<Result> results, ToIntFunction<Result> rankOf) {
        List<Integer> ranks = results.stream().map(r -> rankOf.applyAsInt(r)).toList();
        md.append("| ").append(querySet)
                .append(" | ").append(EvalReport.fraction(count(ranks, 5), ranks.size()))
                .append(" | ").append(EvalReport.fraction(count(ranks, 8), ranks.size()))
                .append(" | ").append(String.format(Locale.ROOT, "%.2f", RetrievalScorer.mrr(ranks)))
                .append(" |\n");
    }

    private static int count(List<Integer> ranks, int k) {
        return (int) ranks.stream().filter(Objects::nonNull).filter(rank -> RetrievalScorer.hitAt(rank, k)).count();
    }

    private static String rank(int rank) {
        return rank == 0 ? ">" + RetrievalScorer.DEPTH : String.valueOf(rank);
    }
}
