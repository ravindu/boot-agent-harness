package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.agent.TokenUsage;
import com.tw.docsagent.eval.AnswerJudge.Check;
import com.tw.docsagent.eval.AnswerJudge.Judgement;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.tool.SourceCatalog.Source;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvalScorerTest {

    private static final String BASE = "https://docs.spring.io/spring-boot/4.1/reference/testing/x.html#";
    private static final Source EXPECTED = new Source("S1", "c1", "Service Connections", BASE + "testing.testcontainers.service-connections");
    private static final Source OTHER = new Source("S2", "c2", "JUnit Extension", BASE + "testing.testcontainers.junit-extension");

    private final GoldenQuestion answerQuestion = new GoldenQuestion("q", Category.HOW_TO, Expect.ANSWER, "How?",
            List.of("testing.testcontainers.service-connections"), List.of("point one", "point two"), List.of("forbidden"));
    private final GoldenQuestion declineQuestion = new GoldenQuestion("kafka", Category.OUT_OF_SCOPE, Expect.DECLINE,
            "Kafka?", List.of(), List.of("says the docs don't cover it"), List.of());

    @Test
    void completeCitedAnswerWithAllKeyPointsPasses() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(RunStatus.COMPLETED, List.of(EXPECTED), List.of(EXPECTED, OTHER), List.of()),
                1200, judgement(List.of(true, true), List.of(false)), null);

        assertThat(r.passed()).isTrue();
        assertThat(r.retrievalHit()).isTrue();
        assertThat(r.citedExpected()).isTrue();
        assertThat(r.keyPointsMet()).isEqualTo(2);
        assertThat(r.retrievedAnchors()).containsExactly(
                "testing.testcontainers.service-connections", "testing.testcontainers.junit-extension");
        assertThat(r.note()).isNull();
    }

    @Test
    void aMissingKeyPointFailsAndIsReportedWithTheJudgesReason() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(RunStatus.COMPLETED, List.of(EXPECTED), List.of(EXPECTED), List.of()),
                1200, judgement(List.of(true, false), List.of(false)), null);

        assertThat(r.passed()).isFalse();
        assertThat(r.keyPointsMet()).isEqualTo(1);
        assertThat(r.missedKeyPoints()).containsExactly("point two (judge: reason 2)");
    }

    @Test
    void sayingAMustNotSayItemFails() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(RunStatus.COMPLETED, List.of(EXPECTED), List.of(EXPECTED), List.of()),
                1200, judgement(List.of(true, true), List.of(true)), null);

        assertThat(r.passed()).isFalse();
        assertThat(r.violations()).containsExactly("forbidden (judge: reason 1)");
    }

    @Test
    void anInventedCitationFailsEvenWithAPerfectChecklist() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(RunStatus.COMPLETED, List.of(EXPECTED), List.of(EXPECTED), List.of("S9")),
                1200, judgement(List.of(true, true), List.of(false)), null);

        assertThat(r.passed()).isFalse();
        assertThat(r.note()).isEqualTo("invented citations [S9]");
    }

    @Test
    void retrievalMissIsMeasuredButACorrectAnswerStillPasses() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(RunStatus.COMPLETED, List.of(OTHER), List.of(OTHER), List.of()),
                1200, judgement(List.of(true, true), List.of(false)), null);

        assertThat(r.passed()).isTrue();
        assertThat(r.retrievalHit()).isFalse();
        assertThat(r.citedExpected()).isFalse();
    }

    @Test
    void aRunThatHitALimitFailsWithItsStatus() {
        AgentRunResult limited = new AgentRunResult(UUID.randomUUID(), RunStatus.MAX_TURNS_REACHED, "maxTurns=10", null,
                List.of(), List.of(), List.of(EXPECTED), 10, TokenUsage.ZERO, BigDecimal.ONE);

        QuestionResult r = EvalScorer.score(answerQuestion, limited, 1200, null, null);

        assertThat(r.passed()).isFalse();
        assertThat(r.retrievalHit()).isTrue();
        assertThat(r.note()).isEqualTo("run ended MAX_TURNS_REACHED (maxTurns=10)");
        assertThat(r.judgeCostUsd()).isZero();
    }

    @Test
    void aJudgeFailureFailsTheQuestionAndSaysWhy() {
        QuestionResult r = EvalScorer.score(answerQuestion, run(RunStatus.COMPLETED, List.of(EXPECTED), List.of(EXPECTED), List.of()),
                1200, null, "judge stopped with max_tokens");

        assertThat(r.passed()).isFalse();
        assertThat(r.note()).isEqualTo("judge failed: judge stopped with max_tokens");
    }

    @Test
    void declineQuestionsPassOnTheChecklistAndHaveNoRetrievalScore() {
        QuestionResult r = EvalScorer.score(declineQuestion, run(RunStatus.COMPLETED, List.of(), List.of(OTHER), List.of()),
                1200, judgement(List.of(true), List.of()), null);

        assertThat(r.passed()).isTrue();
        assertThat(r.retrievalHit()).isNull();
        assertThat(r.citedExpected()).isNull();
    }

    @Test
    void aRefusalCountsAsADeclineButNotAsAnAnswer() {
        AgentRunResult refused = new AgentRunResult(UUID.randomUUID(), RunStatus.REFUSED, "category=cyber", null,
                List.of(), List.of(), List.of(), 1, TokenUsage.ZERO, BigDecimal.ZERO);

        assertThat(EvalScorer.score(declineQuestion, refused, 10, null, null).passed()).isTrue();
        assertThat(EvalScorer.score(declineQuestion, refused, 10, null, null).note())
                .isEqualTo("refused (category=cyber), counted as a decline");
        assertThat(EvalScorer.score(answerQuestion, refused, 10, null, null).passed()).isFalse();
    }

    private static AgentRunResult run(RunStatus status, List<Source> cited, List<Source> retrieved, List<String> invented) {
        return new AgentRunResult(UUID.randomUUID(), status, null, "answer", cited, invented, retrieved, 2,
                TokenUsage.ZERO, new BigDecimal("0.018"));
    }

    private static Judgement judgement(List<Boolean> keyPoints, List<Boolean> mustNotSay) {
        return new Judgement(checks(keyPoints), checks(mustNotSay), new BigDecimal("0.0005"));
    }

    private static List<Check> checks(List<Boolean> present) {
        return java.util.stream.IntStream.range(0, present.size())
                .mapToObj(i -> new Check(i + 1, present.get(i), "reason " + (i + 1)))
                .toList();
    }
}
