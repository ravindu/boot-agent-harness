package com.tw.docsagent.eval;

import static com.tw.docsagent.agent.ScriptedResponses.response;
import static com.tw.docsagent.agent.ScriptedResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.tw.docsagent.agent.CostCalculator;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.config.PricingProperties;
import com.tw.docsagent.config.PricingProperties.ModelPrice;
import com.tw.docsagent.eval.AnswerJudge.Check;
import com.tw.docsagent.eval.AnswerJudge.Judgement;
import com.tw.docsagent.eval.AnswerJudge.Verdict;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.llm.LlmGateway;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnswerJudgeTest {

    private static final String JUDGE_MODEL = "claude-haiku-5-5";
    private static final String ANSWER = "Use the `spring-boot-data-jpa-test` module [S1].\n- Tests roll back after each test.";

    private final LlmGateway llm = mock(LlmGateway.class);
    private final AnswerJudge judge = new AnswerJudge(llm, JUDGE_MODEL, costCalculator());

    private final GoldenQuestion question = new GoldenQuestion("data-jpa-test", Category.HOW_TO, Expect.ANSWER, List.of(),
            "What does @DataJpaTest set up?", List.of("testing.x"), List.of(),
            List.of("Comes from spring-boot-data-jpa-test", "Rolls back after each test"),
            List.of("The Boot 3 package"));

    @Test
    void sendsQuestionAnswerAndNumberedChecklistToTheJudgeModelWithAJsonSchema() {
        when(llm.send(any())).thenReturn(allGood());

        judge.judge(question, ANSWER);

        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(llm).send(sent.capture());
        MessageCreateParams params = sent.getValue();
        assertThat(params.model().asString()).isEqualTo(JUDGE_MODEL);
        assertThat(params.system().orElseThrow().asString()).contains("untrusted data").contains("copied exactly");
        OutputConfig output = params.outputConfig().orElseThrow();
        assertThat(output.format().orElseThrow().schema()._additionalProperties())
                .containsEntry("required", JsonValue.from(List.of("keyPoints", "mustNotSay")));
        assertThat(params.messages().getFirst().content().asString())
                .contains("key points under their number in keyPoints");
        assertThat(params.messages().getFirst().content().asString())
                .contains("<answer>\n" + ANSWER + "\n</answer>")
                .contains("1. Comes from spring-boot-data-jpa-test\n2. Rolls back after each test")
                .contains("<must_not_say>\n1. The Boot 3 package\n</must_not_say>");
    }

    @Test
    void aCleanFirstRoundIsFinalAndPriced() {
        when(llm.send(any())).thenReturn(allGood());

        Judgement judgement = judge.judge(question, ANSWER);

        verify(llm, times(1)).send(any());
        assertThat(judgement.rounds()).isEqualTo(1);
        assertThat(judgement.uncertain()).isFalse();
        assertThat(judgement.keyPoints()).extracting(Verdict::index, Verdict::present).containsExactly(tuple(1, true), tuple(2, true));
        // 1000 input tokens at $1/MTok + 100 output at $5/MTok
        assertThat(judgement.costUsd()).isEqualByComparingTo("0.0015");
    }

    @Test
    void aFailureIsConfirmedByASecondRoundAndBothRoundsArePaid() {
        Message missing = reply(kp(1, true, "Use the spring-boot-data-jpa-test module"), kp(2, false, ""), mns(1, false));
        when(llm.send(any())).thenReturn(missing, missing);

        Judgement judgement = judge.judge(question, ANSWER);

        verify(llm, times(2)).send(any());
        assertThat(judgement.rounds()).isEqualTo(2);
        assertThat(judgement.keyPoints()).extracting(Verdict::present, Verdict::uncertain)
                .containsExactly(tuple(true, false), tuple(false, false));
        assertThat(judgement.costUsd()).isEqualByComparingTo("0.0030");
    }

    @Test
    void roundsThatDisagreeFailTheItemAndMarkItUncertain() {
        when(llm.send(any())).thenReturn(
                reply(kp(1, true, "Use the spring-boot-data-jpa-test module"), kp(2, false, ""), mns(1, false)),
                allGood());

        Judgement judgement = judge.judge(question, ANSWER);

        Verdict second = judgement.keyPoints().get(1);
        assertThat(second.present()).isFalse();
        assertThat(second.uncertain()).isTrue();
        assertThat(second.reason()).startsWith("judges disagree: round 1:");
        assertThat(judgement.uncertain()).isTrue();
    }

    @Test
    void aViolationSeenInOneRoundCountsButIsMarkedUncertain() {
        Message violation = reply(kp(1, true, "Use the spring-boot-data-jpa-test module"),
                kp(2, true, "Tests roll back after each test."), mns(1, true));
        when(llm.send(any())).thenReturn(violation, allGood());

        Verdict verdict = judge.judge(question, ANSWER).mustNotSay().getFirst();

        assertThat(verdict.present()).isTrue();
        assertThat(verdict.uncertain()).isTrue();
    }

    @Test
    void aKeyPointFoundWithAQuoteThatIsNotInTheAnswerCountsAsNotFound() {
        Check invented = new Check(1, true, List.of("Add the spring-boot-starter-data-jpa-test starter"), "named");

        Check checked = AnswerJudge.verifyEvidence(invented, ANSWER);

        assertThat(checked.present()).isFalse();
        assertThat(checked.reason()).startsWith("evidence quote not found in the answer");
    }

    @Test
    void evidenceMatchingIgnoresCaseWhitespaceAndMarkdown() {
        Check quoted = new Check(1, true, List.of("use the spring-boot-data-jpa-test   MODULE"), "named");

        assertThat(AnswerJudge.verifyEvidence(quoted, ANSWER).present()).isTrue();
    }

    @Test
    void evidenceMatchingIgnoresCitationMarkers() {
        assertThat(AnswerJudge.normalize("Use it [S1]. Then roll back [S1, S3].")).isEqualTo("use it. then roll back.");
    }

    @Test
    void anItemStatedAcrossTwoSentencesIsFoundWhenEveryQuoteIsInTheAnswer() {
        Check twoQuotes = new Check(1, true,
                List.of("Use the spring-boot-data-jpa-test module.", "Tests roll back after each test."), "both");
        Check oneInvented = new Check(1, true,
                List.of("Use the spring-boot-data-jpa-test module.", "Tests never roll back."), "both");

        assertThat(AnswerJudge.verifyEvidence(twoQuotes, ANSWER).present()).isTrue();
        assertThat(AnswerJudge.verifyEvidence(oneInvented, ANSWER).present()).isFalse();
    }

    @Test
    void aViolationWithoutARealQuoteDoesNotCount() {
        Message unquotedViolation = reply(kp(1, true, "Use the spring-boot-data-jpa-test module"),
                kp(2, true, "Tests roll back after each test."), mns(1, true).replace("Use the spring-boot-data-jpa-test module", "org.springframework.boot.test.autoconfigure.orm.jpa"));
        when(llm.send(any())).thenReturn(unquotedViolation, unquotedViolation);

        Verdict verdict = judge.judge(question, ANSWER).mustNotSay().getFirst();

        assertThat(verdict.present()).isFalse();
    }

    @Test
    void theSchemaRequiresOneEntryPerItemSoTheApiCannotSkipOne() {
        Map<String, Object> schema = AnswerJudge.schema(3, 1);

        assertThat(schema).containsEntry("required", List.of("keyPoints", "mustNotSay"));
        assertThat(field(schema, "keyPoints")).containsEntry("required", List.of("1", "2", "3"))
                .containsEntry("additionalProperties", false);
        assertThat(field(schema, "mustNotSay")).containsEntry("required", List.of("1"));
    }

    @Test
    void noMustNotSayItemsMeansNoMustNotSayProperty() {
        Map<String, Object> schema = AnswerJudge.schema(2, 0);

        assertThat(schema).containsEntry("required", List.of("keyPoints"));
        assertThat(field(schema, "keyPoints")).isNotNull();
        assertThat(field(schema, "mustNotSay")).isNull();
    }

    @Test
    void aQuestionWithoutMustNotSayItemsAcceptsAReplyWithoutThem() {
        GoldenQuestion noForbidden = new GoldenQuestion("q", Category.HOW_TO, Expect.ANSWER, List.of(), "Q?",
                List.of("testing.x"), List.of(), List.of("Comes from spring-boot-data-jpa-test"), List.of());
        when(llm.send(any())).thenReturn(response(StopReason.END_TURN, 1000,
                text("{\"keyPoints\": {" + kp(1, true, "Use the spring-boot-data-jpa-test module") + "}}")));

        Judgement judgement = judge.judge(noForbidden, ANSWER);

        assertThat(judgement.keyPoints()).extracting(Verdict::present).containsExactly(true);
        assertThat(judgement.mustNotSay()).isEmpty();
    }

    @Test
    void stillRejectsAReplyThatSkipsAKeyPoint() {
        when(llm.send(any())).thenReturn(reply(kp(1, true, "Use the spring-boot-data-jpa-test module"), mns(1, false)));

        assertThatThrownBy(() -> judge.judge(question, ANSWER))
                .isInstanceOf(AnswerJudge.JudgeException.class)
                .hasMessage("judge returned keyPoints [1], expected [1, 2]");
    }

    @Test
    void rejectsATruncatedReply() {
        when(llm.send(any())).thenReturn(response(StopReason.MAX_TOKENS, 1000, text("{\"keyPo")));

        assertThatThrownBy(() -> judge.judge(question, ANSWER)).hasMessage("judge stopped with max_tokens");
    }

    private static Message allGood() {
        return reply(kp(1, true, "Use the spring-boot-data-jpa-test module"),
                kp(2, true, "Tests roll back after each test."), mns(1, false));
    }

    /** Builds a judge reply; key point entries first, then must-not-say entries (marked by {@link #mns}). */
    private static Message reply(String... entries) {
        List<String> keyPoints = java.util.Arrays.stream(entries).filter(e -> !e.startsWith("M")).toList();
        List<String> mustNotSay = java.util.Arrays.stream(entries).filter(e -> e.startsWith("M")).map(e -> e.substring(1)).toList();
        String json = "{\"keyPoints\": {" + String.join(",", keyPoints) + "}, \"mustNotSay\": {" + String.join(",", mustNotSay) + "}}";
        return response(StopReason.END_TURN, 1000, text(json));
    }

    /** One entry of a numbered reply object: {@code "1": {"present": ..., "evidence": ..., "reason": ...}}. */
    private static String kp(int index, boolean present, String evidence) {
        String quotes = evidence.isEmpty() ? "[]" : "[\"" + evidence + "\"]";
        return "\"%d\": {\"present\": %b, \"evidence\": %s, \"reason\": \"reason %d\"}"
                .formatted(index, present, quotes, index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    private static String mns(int index, boolean present) {
        return "M" + kp(index, present, present ? "Use the spring-boot-data-jpa-test module" : "");
    }

    private static CostCalculator costCalculator() {
        ModelPrice price = new ModelPrice(new BigDecimal("1.00"), new BigDecimal("5.00"),
                new BigDecimal("1.25"), new BigDecimal("0.10"));
        PricingProperties pricing = new PricingProperties(Map.of(JUDGE_MODEL, price, "agent", price));
        AnthropicProperties anthropic = new AnthropicProperties("agent", "medium", 1000, JUDGE_MODEL,
                Duration.ofSeconds(10), 0);
        return new CostCalculator(pricing, anthropic);
    }
}
