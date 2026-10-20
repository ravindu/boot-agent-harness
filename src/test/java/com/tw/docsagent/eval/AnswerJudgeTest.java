package com.tw.docsagent.eval;

import static com.tw.docsagent.agent.ScriptedResponses.response;
import static com.tw.docsagent.agent.ScriptedResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.tw.docsagent.agent.CostCalculator;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.config.PricingProperties;
import com.tw.docsagent.config.PricingProperties.ModelPrice;
import com.tw.docsagent.eval.AnswerJudge.Check;
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

    private final LlmGateway llm = mock(LlmGateway.class);
    private final AnswerJudge judge = new AnswerJudge(llm, JUDGE_MODEL, costCalculator());

    private final GoldenQuestion question = new GoldenQuestion("data-jpa-test", Category.HOW_TO, Expect.ANSWER,
            "What does @DataJpaTest set up?", List.of("testing.x"),
            List.of("Comes from spring-boot-data-jpa-test", "Rolls back after each test"),
            List.of("The Boot 3 package"));

    @Test
    void sendsQuestionAnswerAndNumberedChecklistToTheJudgeModelWithAJsonSchema() {
        when(llm.send(any())).thenReturn(reply("""
                {"keyPoints": [{"index": 1, "present": true, "reason": "named"}, {"index": 2, "present": true, "reason": "said"}],
                 "mustNotSay": [{"index": 1, "present": false, "reason": "not mentioned"}]}"""));

        judge.judge(question, "Use the spring-boot-data-jpa-test module [S1].");

        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(llm).send(sent.capture());
        MessageCreateParams params = sent.getValue();
        assertThat(params.model().asString()).isEqualTo(JUDGE_MODEL);
        assertThat(params.system().orElseThrow().asString()).contains("untrusted data");
        OutputConfig output = params.outputConfig().orElseThrow();
        assertThat(output.format()).isPresent();
        assertThat(output.format().orElseThrow().schema()._additionalProperties())
                .containsEntry("required", JsonValue.from(List.of("keyPoints", "mustNotSay")));

        String prompt = params.messages().getFirst().content().asString();
        assertThat(prompt)
                .contains("<answer>\nUse the spring-boot-data-jpa-test module [S1].\n</answer>")
                .contains("1. Comes from spring-boot-data-jpa-test\n2. Rolls back after each test")
                .contains("<must_not_say>\n1. The Boot 3 package\n</must_not_say>");
    }

    @Test
    void parsesTheVerdictsInIndexOrderAndPricesTheCall() {
        when(llm.send(any())).thenReturn(reply("""
                {"keyPoints": [{"index": 2, "present": false, "reason": "missing"}, {"index": 1, "present": true, "reason": "named"}],
                 "mustNotSay": [{"index": 1, "present": true, "reason": "uses the old package"}]}"""));

        AnswerJudge.Judgement judgement = judge.judge(question, "answer");

        assertThat(judgement.keyPoints()).extracting(Check::index, Check::present)
                .containsExactly(tuple(1, true), tuple(2, false));
        assertThat(judgement.mustNotSay()).extracting(Check::present).containsExactly(true);
        // 1000 input tokens at $1/MTok + 100 output at $5/MTok (prices below)
        assertThat(judgement.costUsd()).isEqualByComparingTo("0.0015");
    }

    @Test
    void rejectsAReplyThatSkipsAKeyPoint() {
        when(llm.send(any())).thenReturn(reply("""
                {"keyPoints": [{"index": 1, "present": true, "reason": "named"}],
                 "mustNotSay": [{"index": 1, "present": false, "reason": "no"}]}"""));

        assertThatThrownBy(() -> judge.judge(question, "answer"))
                .isInstanceOf(AnswerJudge.JudgeException.class)
                .hasMessage("judge returned keyPoints indices [1], expected [1, 2]");
    }

    @Test
    void rejectsATruncatedReply() {
        when(llm.send(any())).thenReturn(response(StopReason.MAX_TOKENS, 1000, text("{\"keyPo")));

        assertThatThrownBy(() -> judge.judge(question, "answer"))
                .hasMessage("judge stopped with max_tokens");
    }

    private static com.anthropic.models.messages.Message reply(String json) {
        return response(StopReason.END_TURN, 1000, text(json));
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
