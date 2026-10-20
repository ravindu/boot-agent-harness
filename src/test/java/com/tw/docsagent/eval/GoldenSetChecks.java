package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.eval.GoldenQuestion.Trait;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks the golden set against what was actually ingested, before an eval spends time or money.
 * A typo'd anchor or phrase would otherwise count as a miss on every run.
 */
final class GoldenSetChecks {

    private GoldenSetChecks() {
    }

    static void assertExpectedSectionsWereIngested(List<GoldenQuestion> questions, JdbcClient jdbc) {
        Set<String> ingested = Set.copyOf(jdbc.sql("SELECT DISTINCT metadata->>'anchor' FROM vector_store")
                .query(String.class).list());
        List<String> unknown = questions.stream()
                .flatMap(q -> q.expectedSources().stream().filter(a -> !ingested.contains(a)).map(a -> q.id() + ": " + a))
                .toList();
        assertThat(unknown).as("expectedSources anchors that are not in the ingested docs").isEmpty();
    }

    /** A phrase matching no chunk can never hit; one matching several can't tell which chunk was found. */
    static void assertEachExpectedTextIsInExactlyOneChunk(List<GoldenQuestion> questions, Map<String, String> chunks) {
        List<String> normalized = chunks.values().stream().map(EvalScorer::normalize).toList();
        List<String> problems = questions.stream()
                .flatMap(q -> q.expectedText().stream().map(phrase -> {
                    String p = EvalScorer.normalize(phrase);
                    long matches = normalized.stream().filter(text -> text.contains(p)).count();
                    return matches == 1 ? null : q.id() + ": '" + phrase + "' matches " + matches + " chunks";
                }))
                .filter(Objects::nonNull)
                .toList();
        assertThat(problems).as("expectedText phrases must each occur in exactly one ingested chunk").isEmpty();
    }

    /**
     * The paraphrased / deep-detail / table-or-code tags must match what {@link DerivedTraits} computes
     * from the chunks. Run after {@link #assertEachExpectedTextIsInExactlyOneChunk}.
     */
    static void assertDerivedTraitsMatchTheDocs(List<GoldenQuestion> questions, List<DerivedTraits.Chunk> chunks) {
        List<String> problems = questions.stream().map(q -> {
            List<DerivedTraits.Chunk> answerChunks = q.expectedText().stream()
                    .map(phrase -> chunkContaining(phrase, chunks))
                    .toList();
            Set<Trait> computed = DerivedTraits.derive(q, answerChunks);
            Set<Trait> tagged = EnumSet.noneOf(Trait.class);
            tagged.addAll(q.traits());
            tagged.retainAll(DerivedTraits.DERIVED);
            return computed.equals(tagged) ? null : q.id() + ": tagged " + tagged + ", the docs give " + computed;
        }).filter(Objects::nonNull).toList();
        assertThat(problems).as("derived traits that don't match the ingested docs").isEmpty();
    }

    private static DerivedTraits.Chunk chunkContaining(String phrase, List<DerivedTraits.Chunk> chunks) {
        String wanted = EvalScorer.normalize(phrase);
        return chunks.stream().filter(c -> EvalScorer.normalize(c.text()).contains(wanted)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no chunk contains '" + phrase + "'"));
    }

    /** Every ingested chunk with its position in its section. */
    static List<DerivedTraits.Chunk> chunksWithIndex(JdbcClient jdbc) {
        return jdbc.sql("SELECT content, (metadata->>'chunkIndex')::int AS chunk_index FROM vector_store")
                .query((rs, row) -> new DerivedTraits.Chunk(rs.getString("content"), rs.getInt("chunk_index")))
                .list();
    }

    /** Every ingested chunk's text by id ({@code vector_store.id}). */
    static Map<String, String> chunkTexts(JdbcClient jdbc) {
        Map<String, String> texts = new LinkedHashMap<>();
        jdbc.sql("SELECT id::text AS id, content FROM vector_store")
                .query((rs, row) -> Map.entry(rs.getString("id"), rs.getString("content")))
                .list()
                .forEach(e -> texts.put(e.getKey(), e.getValue()));
        return texts;
    }
}
