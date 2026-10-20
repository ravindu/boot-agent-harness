package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.agent.TokenUsage;
import com.tw.docsagent.eval.AnswerJudge.Judgement;
import com.tw.docsagent.eval.AnswerJudge.Verdict;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.tool.SourceCatalog.Source;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EvalScorerTest {

    private static final String BASE = "https://docs.spring.io/spring-boot/4.1/reference/testing/x.html#";
    /** Two chunks of the same section: the example chunk (has the answer) and the table chunk. */
    private static final Source EXAMPLE = new Source("S1", "chunk-0", "Service Connections", BASE + "testing.testcontainers.service-connections");
    private static final Source TABLE = new Source("S2", "chunk-1", "Service Connections", BASE + "testing.testcontainers.service-connections");
    private static final Source OTHER = new Source("S3", "chunk-9", "JUnit Extension", BASE + "testing.testcontainers.junit-extension");
    private static final EvalScorer.Retrieval RETRIEVAL = new EvalScorer.Retrieval(List.of("@ServiceConnection"), Map.of(
            "chunk-0", "@Container\n\t@ServiceConnection\n\tstatic Neo4jContainer neo4j",
            "chunk-1", "| `JdbcConnectionDetails` | Containers of type `JdbcDatabaseContainer`",
            "chunk-9", "The JUnit extension"));

    private final GoldenQuestion answerQuestion = new GoldenQuestion("q", Category.HOW_TO, Expect.ANSWER, List.of(),
            "How?", List.of("testing.testcontainers.service-connections"), List.of("@ServiceConnection static Neo4jContainer"),
            List.of("point one", "point two"), List.of("forbidden"));
    private final GoldenQuestion declineQuestion = new GoldenQuestion("kafka", Category.OUT_OF_SCOPE, Expect.DECLINE,
            List.of(), "Kafka?", List.of(), List.of(), List.of("says the docs don't cover it"), List.of());

    @Test
    void completeCitedAnswerWithAllKeyPointsPasses() {
        QuestionResult r = score(answerQuestion, run(List.of(EXAMPLE), List.of(EXAMPLE, OTHER), List.of()),
                judgement(List.of(true, true), List.of(false)));

        assertThat(r.passed()).isTrue();
        assertThat(r.retrievalHit()).isTrue();
        assertThat(r.passageHit()).isTrue();
        assertThat(r.citedExpected()).isTrue();
        assertThat(r.keyPointsMet()).isEqualTo(2);
        assertThat(r.retrievedAnchors()).containsExactly(
                "testing.testcontainers.service-connections", "testing.testcontainers.junit-extension");
        assertThat(r.retrievedChunkIds()).containsExactly("chunk-0", "chunk-9");
        assertThat(r.searchQueries()).containsExactly("@ServiceConnection");
        assertThat(r.note()).isNull();
    }

    @Test
    void theRightSectionThroughTheWrongChunkIsASectionHitButAPassageMiss() {
        QuestionResult r = score(answerQuestion, run(List.of(TABLE), List.of(TABLE), List.of()),
                judgement(List.of(false, false), List.of(false)));

        assertThat(r.retrievalHit()).isTrue();
        assertThat(r.passageHit()).isFalse();
    }

    @Test
    void passageHitNeedsEveryPhraseAndIgnoresWhitespace() {
        Map<String, String> texts = Map.of("a", "first   phrase\nhere", "b", "second phrase");

        assertThat(EvalScorer.passageHit(List.of("first phrase here", "second phrase"), List.of("a", "b"), texts)).isTrue();
        assertThat(EvalScorer.passageHit(List.of("first phrase here", "second phrase"), List.of("a"), texts)).isFalse();
    }

    @Test
    void aMissingKeyPointFailsAndIsReportedWithTheJudgesReason() {
        QuestionResult r = score(answerQuestion, run(List.of(EXAMPLE), List.of(EXAMPLE), List.of()),
                judgement(List.of(true, false), List.of(false)));

        assertThat(r.passed()).isFalse();
        assertThat(r.keyPointsMet()).isEqualTo(1);
        assertThat(r.missedKeyPoints()).containsExactly("point two (judge: reason 2)");
    }

    @Test
    void uncertainVerdictsAreListedForAHumanToCheck() {
        Judgement judgement = new Judgement(
                List.of(new Verdict(1, true, false, "ok"), new Verdict(2, false, true, "judges disagree: …")),
                List.of(new Verdict(1, false, false, "ok")), 2, new BigDecimal("0.001"));

        QuestionResult r = score(answerQuestion, run(List.of(EXAMPLE), List.of(EXAMPLE), List.of()), judgement);

        assertThat(r.passed()).isFalse();
        assertThat(r.uncertain()).containsExactly("key point: point two");
        assertThat(r.judgeRounds()).isEqualTo(2);
    }

    @Test
    void sayingAMustNotSayItemFails() {
        QuestionResult r = score(answerQuestion, run(List.of(EXAMPLE), List.of(EXAMPLE), List.of()),
                judgement(List.of(true, true), List.of(true)));

        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).containsExactly("forbidden (judge: reason 1)");
    }

    @Test
    void anInventedCitationFailsEvenWithAPerfectChecklist() {
        QuestionResult r = score(answerQuestion, run(List.of(EXAMPLE), List.of(EXAMPLE), List.of("S9")),
                judgement(List.of(true, true), List.of(false)));

        assertThat(r.passed()).isFalse();
        assertThat(r.note()).isEqualTo("invented citations [S9]");
    }

    @Test
    void retrievalMissIsMeasuredButACorrectAnswerStillPasses() {
        QuestionResult r = score(answerQuestion, run(List.of(OTHER), List.of(OTHER), List.of()),
                judgement(List.of(true, true), List.of(false)));

        assertThat(r.passed()).isTrue();
        assertThat(r.retrievalHit()).isFalse();
        assertThat(r.passageHit()).isFalse();
        assertThat(r.citedExpected()).isFalse();
    }

    @Test
    void aRunThatHitALimitFailsWithItsStatus() {
        AgentRunResult limited = new AgentRunResult(UUID.randomUUID(), RunStatus.MAX_TURNS_REACHED, "maxTurns=10", null,
                List.of(), List.of(), List.of(EXAMPLE), 10, TokenUsage.ZERO, BigDecimal.ONE);

        QuestionResult r = score(answerQuestion, limited, null);

        assertThat(r.passed()).isFalse();
        assertThat(r.retrievalHit()).isTrue();
        assertThat(r.note()).isEqualTo("run ended MAX_TURNS_REACHED (maxTurns=10)");
        assertThat(r.judgeCostUsd()).isZero();
        assertThat(r.judgeRounds()).isZero();
    }

    @Test
    void aJudgeFailureFailsTheQuestionAndSaysWhy() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(List.of(EXAMPLE), List.of(EXAMPLE), List.of()), 1200,
                RETRIEVAL, null, "judge stopped with max_tokens");

        assertThat(r.passed()).isFalse();
        assertThat(r.note()).isEqualTo("judge failed: judge stopped with max_tokens");
    }

    @Test
    void declineQuestionsPassOnTheChecklistAndHaveNoRetrievalScore() {
        QuestionResult r = score(declineQuestion, run(List.of(), List.of(OTHER), List.of()),
                judgement(List.of(true), List.of()));

        assertThat(r.passed()).isTrue();
        assertThat(r.retrievalHit()).isNull();
        assertThat(r.passageHit()).isNull();
        assertThat(r.citedExpected()).isNull();
    }

    @Test
    void aRefusalCountsAsADeclineButNotAsAnAnswer() {
        AgentRunResult refused = new AgentRunResult(UUID.randomUUID(), RunStatus.REFUSED, "category=cyber", null,
                List.of(), List.of(), List.of(), 1, TokenUsage.ZERO, BigDecimal.ZERO);

        assertThat(score(declineQuestion, refused, null).passed()).isTrue();
        assertThat(score(declineQuestion, refused, null).note()).isEqualTo("refused (category=cyber), counted as a decline");
        assertThat(score(answerQuestion, refused, null).passed()).isFalse();
    }

    private static QuestionResult score(GoldenQuestion question, AgentRunResult run, Judgement judgement) {
        return EvalScorer.score(question, run, 1200, RETRIEVAL, judgement, null);
    }

    private static AgentRunResult run(List<Source> cited, List<Source> retrieved, List<String> invented) {
        return new AgentRunResult(UUID.randomUUID(), RunStatus.COMPLETED, null, "answer", cited, invented, retrieved, 2,
                TokenUsage.ZERO, new BigDecimal("0.018"));
    }

    private static Judgement judgement(List<Boolean> keyPoints, List<Boolean> mustNotSay) {
        return new Judgement(verdicts(keyPoints), verdicts(mustNotSay), 1, new BigDecimal("0.0005"));
    }

    private static List<Verdict> verdicts(List<Boolean> present) {
        return IntStream.range(0, present.size())
                .mapToObj(i -> new Verdict(i + 1, present.get(i), false, "reason " + (i + 1)))
                .toList();
    }
}
