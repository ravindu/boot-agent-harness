package com.tw.docsagent.agent;

import static com.tw.docsagent.agent.ScriptedResponses.MODEL;
import static com.tw.docsagent.agent.ScriptedResponses.response;
import static com.tw.docsagent.agent.ScriptedResponses.text;
import static com.tw.docsagent.agent.ScriptedResponses.toolUse;
import static com.tw.docsagent.agent.ScriptedResponses.toolUseResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.tw.docsagent.config.AgentLimitsProperties;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.config.PricingProperties;
import com.tw.docsagent.llm.LlmGateway;
import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import com.tw.docsagent.tool.AgentTool;
import com.tw.docsagent.tool.ToolContext;
import com.tw.docsagent.tool.ToolExecutor;
import com.tw.docsagent.tool.ToolRegistry;
import com.tw.docsagent.tool.ToolResult;
import com.tw.docsagent.trace.ModelCallStep;
import com.tw.docsagent.trace.ToolCallStep;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The agent loop against a scripted fake model: every response Claude "sends" is written out in the
 * test, so each branch of the loop runs deterministically and without an API key.
 */
class AgentLoopTest {

    private final LlmGateway llm = mock(LlmGateway.class);
    private final InMemoryRunRecorder recorder = new InMemoryRunRecorder();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger searchCalls = new AtomicInteger();

    /** Stands in for searchDocs: registers one source per call, so S1, S2... exist like in a real run. */
    private final AgentTool fakeSearch = new AgentTool() {
        public String name() { return "searchDocs"; }
        public String description() { return "search"; }
        public Map<String, Object> inputProperties() { return Map.of("query", Map.of("type", "string")); }
        public List<String> requiredInputs() { return List.of("query"); }
        public ToolResult execute(Map<String, Object> input, ToolContext context) {
            int n = searchCalls.incrementAndGet();
            String label = context.sources().register("chunk-" + n, "Title " + n, "https://docs/" + n).label();
            return ToolResult.ok("<doc id=\"" + label + "\">result for " + input.get("query") + "</doc>");
        }
    };

    private AgentLoop loop(AgentLimitsProperties limits) {
        AnthropicProperties anthropic = new AnthropicProperties(MODEL, "medium", 16000, "claude-haiku-5-5",
                Duration.ofSeconds(60), 2);
        PricingProperties.ModelPrice price = new PricingProperties.ModelPrice(
                new BigDecimal("2.00"), new BigDecimal("10.00"), new BigDecimal("2.50"), new BigDecimal("0.20"));
        PricingProperties pricing = new PricingProperties(Map.of(MODEL, price, "claude-haiku-5-5", price));
        DocsSourceProperties docs = new DocsSourceProperties("r", "v4.1.1", "4.1.1", "https://docs", List.of("p"));
        ToolRegistry registry = new ToolRegistry(List.of(fakeSearch));
        return new AgentLoop(llm, registry, new ToolExecutor(registry), new SystemPrompt(docs),
                new CostCalculator(pricing, anthropic), recorder, anthropic, limits, clock);
    }

    private AgentLoop loop() {
        return loop(new AgentLimitsProperties(5, 200_000, new BigDecimal("0.50"), Duration.ofMinutes(2)));
    }

    // --- helpers ------------------------------------------------------------------------------

    private List<MessageCreateParams> sentRequests(int count) {
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(llm, times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    private static List<ToolResultBlockParam> toolResults(MessageParam message) {
        return message.content().asBlockParams().stream()
                .flatMap(block -> block.toolResult().stream())
                .toList();
    }

    // --- tests ----------------------------------------------------------------------------------

    @Test
    void toolRoundTripEndsWithACitedAnswer() {
        when(llm.send(any())).thenReturn(
                toolUseResponse(toolUse("toolu_1", "searchDocs", Map.of("query", "@ServiceConnection"))),
                response(StopReason.END_TURN, 2000, text("Annotate the container field with @ServiceConnection [S1].")));

        AgentRunResult result = loop().run("How do I use @ServiceConnection?");

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.answer()).isEqualTo("Annotate the container field with @ServiceConnection [S1].");
        assertThat(result.citations()).extracting("label", "url").containsExactly(
                org.assertj.core.groups.Tuple.tuple("S1", "https://docs/1"));
        assertThat(result.inventedCitations()).isEmpty();
        assertThat(result.turns()).isEqualTo(2);
        assertThat(result.usage().input()).isEqualTo(3000);
        // 3000 in * $2 + 200 out * $10, per million
        assertThat(result.costUsd()).isEqualByComparingTo("0.008");

        // Second request: user question, Claude's tool_use turn, our tool_result, matched by id
        MessageCreateParams second = sentRequests(2).get(1);
        assertThat(second.messages()).hasSize(3);
        assertThat(second.messages().get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        ToolResultBlockParam toolResult = toolResults(second.messages().get(2)).getFirst();
        assertThat(toolResult.toolUseId()).isEqualTo("toolu_1");
        assertThat(toolResult.isError()).contains(false);
        assertThat(toolResult.content().orElseThrow().asString()).contains("result for @ServiceConnection");

        // Trace: model call, tool call, model call; history persisted message by message
        assertThat(recorder.steps).hasSize(3);
        assertThat(recorder.steps.get(1)).isInstanceOfSatisfying(ToolCallStep.class, step -> {
            assertThat(step.toolName()).isEqualTo("searchDocs");
            assertThat(step.inputJson()).isEqualTo("{\"query\":\"@ServiceConnection\"}");
        });
        assertThat(recorder.messages).hasSize(4);
        assertThat(recorder.finished).isEqualTo(result);
    }

    @Test
    void requestCarriesSystemPromptStrictToolsAndEffort() {
        when(llm.send(any())).thenReturn(response(StopReason.END_TURN, 10, text("Hi")));

        loop().run("Hello");

        MessageCreateParams request = sentRequests(1).getFirst();
        assertThat(request.model().asString()).isEqualTo(MODEL);
        assertThat(request.system().orElseThrow().asString()).contains("Spring Boot 4.1.1", "[S2]");
        assertThat(request.tools().orElseThrow()).singleElement()
                .satisfies(tool -> assertThat(tool.asTool().strict()).contains(true));
        assertThat(request.outputConfig().orElseThrow().effort()).hasValueSatisfying(
                effort -> assertThat(effort.asString()).isEqualTo("medium"));
    }

    @Test
    void parallelToolCallsAreAnsweredInOneMessage() {
        when(llm.send(any())).thenReturn(
                toolUseResponse(
                        toolUse("toolu_a", "searchDocs", Map.of("query", "a")),
                        toolUse("toolu_b", "searchDocs", Map.of("query", "b"))),
                response(StopReason.END_TURN, 10, text("Both [S1][S2].")));

        AgentRunResult result = loop().run("q");

        MessageParam toolResultMessage = sentRequests(2).get(1).messages().get(2);
        assertThat(toolResults(toolResultMessage)).extracting(ToolResultBlockParam::toolUseId)
                .containsExactly("toolu_a", "toolu_b");
        assertThat(result.citations()).hasSize(2);
    }

    @Test
    void unknownToolIsReportedToClaudeAndTheRunContinues() {
        when(llm.send(any())).thenReturn(
                toolUseResponse(toolUse("toolu_x", "deleteDatabase", Map.of())),
                response(StopReason.END_TURN, 10, text("I can only search the docs.")));

        AgentRunResult result = loop().run("q");

        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        ToolResultBlockParam toolResult = toolResults(sentRequests(2).get(1).messages().get(2)).getFirst();
        assertThat(toolResult.isError()).contains(true);
        assertThat(toolResult.content().orElseThrow().asString()).contains("Unknown tool 'deleteDatabase'");
    }

    @Test
    void thinkingBlocksAreSentBackUnchanged() {
        ThinkingBlock thinking = ThinkingBlock.builder().thinking("").signature("sig-abc").build();
        when(llm.send(any())).thenReturn(
                response(StopReason.TOOL_USE, 10, thinking, toolUse("toolu_1", "searchDocs", Map.of("query", "x"))),
                response(StopReason.END_TURN, 10, text("Done [S1].")));

        loop().run("q");

        List<ContentBlockParam> assistantTurn = sentRequests(2).get(1).messages().get(1).content().asBlockParams();
        assertThat(assistantTurn.getFirst().thinking()).hasValueSatisfying(
                block -> assertThat(block.signature()).isEqualTo("sig-abc"));
        assertThat(assistantTurn.get(1).toolUse()).isPresent();
    }

    @Test
    void citationOfANeverRetrievedSourceIsFlaggedAsInvented() {
        when(llm.send(any())).thenReturn(
                toolUseResponse(toolUse("toolu_1", "searchDocs", Map.of("query", "x"))),
                response(StopReason.END_TURN, 10, text("True [S1], also true [S7].")));

        AgentRunResult result = loop().run("q");

        assertThat(result.citations()).extracting("label").containsExactly("S1");
        assertThat(result.inventedCitations()).containsExactly("S7");
    }

    @Test
    void stopsAtMaxTurns() {
        when(llm.send(any())).thenAnswer(inv -> toolUseResponse(toolUse("searchDocs", Map.of("query", "again"))));

        AgentRunResult result = loop(new AgentLimitsProperties(3, 200_000, new BigDecimal("0.50"), Duration.ofMinutes(2)))
                .run("q");

        assertThat(result.status()).isEqualTo(RunStatus.MAX_TURNS_REACHED);
        assertThat(result.statusDetail()).isEqualTo("maxTurns=3");
        assertThat(result.turns()).isEqualTo(3);
        sentRequests(3);
    }

    @Test
    void stopsWhenTheTokenBudgetIsUsedUp() {
        when(llm.send(any())).thenReturn(response(StopReason.TOOL_USE, 150_000,
                toolUse("toolu_1", "searchDocs", Map.of("query", "x"))));

        AgentRunResult result = loop(new AgentLimitsProperties(10, 100_000, new BigDecimal("5.00"), Duration.ofMinutes(2)))
                .run("q");

        assertThat(result.status()).isEqualTo(RunStatus.BUDGET_EXCEEDED);
        assertThat(result.statusDetail()).startsWith("tokens=150100");
        assertThat(result.turns()).isEqualTo(1);
    }

    @Test
    void stopsWhenTheCostBudgetIsUsedUp() {
        // 100k input tokens * $2/M = $0.20 per call
        when(llm.send(any())).thenReturn(response(StopReason.TOOL_USE, 100_000,
                toolUse("toolu_1", "searchDocs", Map.of("query", "x"))));

        AgentRunResult result = loop(new AgentLimitsProperties(10, 10_000_000, new BigDecimal("0.30"), Duration.ofMinutes(2)))
                .run("q");

        assertThat(result.status()).isEqualTo(RunStatus.BUDGET_EXCEEDED);
        assertThat(result.statusDetail()).startsWith("costUsd=0.402");
        assertThat(result.turns()).isEqualTo(2);
    }

    @Test
    void stopsWhenTheRunTimesOut() {
        when(llm.send(any())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(70));
            return toolUseResponse(toolUse("searchDocs", Map.of("query", "x")));
        });

        AgentRunResult result = loop().run("q");

        assertThat(result.status()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(result.turns()).isEqualTo(2);
    }

    @Test
    void refusalEndsTheRunWithItsCategory() {
        Message refusal = response(StopReason.REFUSAL, 10).toBuilder()
                .stopDetails(RefusalStopDetails.builder()
                        .category(RefusalStopDetails.Category.CYBER).explanation("declined").build())
                .build();
        when(llm.send(any())).thenReturn(refusal);

        AgentRunResult result = loop().run("q");

        assertThat(result.status()).isEqualTo(RunStatus.REFUSED);
        assertThat(result.statusDetail()).isEqualTo("category=cyber");
        assertThat(recorder.steps.getFirst()).isInstanceOfSatisfying(ModelCallStep.class,
                step -> assertThat(step.stopReason()).isEqualTo("refusal"));
    }

    @Test
    void maxTokensEndsTheRunAsTruncatedWithThePartialText() {
        when(llm.send(any())).thenReturn(response(StopReason.MAX_TOKENS, 10, text("Partial ans")));

        AgentRunResult result = loop().run("q");

        assertThat(result.status()).isEqualTo(RunStatus.TRUNCATED);
        assertThat(result.answer()).isEqualTo("Partial ans");
    }

    @Test
    void apiFailureEndsTheRunAsFailed() {
        when(llm.send(any())).thenThrow(new AnthropicIoException("connection reset"));

        AgentRunResult result = loop().run("q");

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.statusDetail()).isEqualTo("model call failed: AnthropicIoException");
        assertThat(recorder.finished).isEqualTo(result);
    }

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
