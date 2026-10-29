package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalScorerTest {

    private static final List<String> RESULTS = List.of(
            "| `JdbcConnectionDetails` | Containers of type `JdbcDatabaseContainer`",
            "@Container\n\t@ServiceConnection\n\tstatic Neo4jContainer neo4j",
            "class MyRandomPortRestTestClientAssertJTests {");

    @Test
    void rankIsTheOneBasedPositionOfTheFirstChunkContainingThePhrase() {
        assertThat(RetrievalScorer.rank("@ServiceConnection static Neo4jContainer", RESULTS)).isEqualTo(2);
        assertThat(RetrievalScorer.rank("JdbcConnectionDetails", RESULTS)).isEqualTo(1);
        assertThat(RetrievalScorer.rank("not there", RESULTS)).isZero();
    }

    @Test
    void aQuestionCountsAtItsWorstPhraseBecauseTheAnswerNeedsEveryExpectedChunk() {
        int rank = RetrievalScorer.questionRank(
                List.of("JdbcConnectionDetails", "MyRandomPortRestTestClientAssertJTests"), List.of(RESULTS));

        assertThat(rank).isEqualTo(3);
        assertThat(RetrievalScorer.questionRank(List.of("JdbcConnectionDetails", "missing"), List.of(RESULTS))).isZero();
    }

    @Test
    void overSeveralQueriesEachPhraseCountsAtItsBestRank() {
        List<String> secondQuery = List.of("@ServiceConnection static Neo4jContainer neo4j", "other");

        assertThat(RetrievalScorer.questionRank(List.of("@ServiceConnection static Neo4jContainer"),
                List.of(RESULTS, secondQuery))).isEqualTo(1);
    }

    @Test
    void hitAtKAndMeanReciprocalRank() {
        assertThat(RetrievalScorer.hitAt(5, 5)).isTrue();
        assertThat(RetrievalScorer.hitAt(6, 5)).isFalse();
        assertThat(RetrievalScorer.hitAt(0, 5)).isFalse();
        // (1 + 1/2 + 0 + 1/4) / 4
        assertThat(RetrievalScorer.mrr(List.of(1, 2, 0, 4))).isCloseTo(0.4375, within(1e-9));
    }
}
