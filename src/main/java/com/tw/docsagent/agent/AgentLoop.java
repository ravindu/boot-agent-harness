package com.tw.docsagent.agent;

import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.tw.docsagent.config.AgentLimitsProperties;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.llm.LlmGateway;
import com.tw.docsagent.tool.ToolContext;
import com.tw.docsagent.tool.ToolExecutor;
import com.tw.docsagent.tool.ToolRegistry;
import com.tw.docsagent.tool.ToolResult;
import com.tw.docsagent.trace.ModelCallStep;
import com.tw.docsagent.trace.RunRecorder;
import com.tw.docsagent.trace.ToolCallStep;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The hand-written agent loop.
 *
 * <pre>
 * messages = [user: question]
 * loop:
 *   check limits (turns, tokens, cost, time)      → stop with an explicit status
 *   response = Claude(system, tools, messages)
 *   messages += response (unchanged, thinking included)
 *   stop_reason:
 *     tool_use   → run every tool_use, messages += [user: all tool_results], loop
 *     end_turn   → answer; check citations; COMPLETED
 *     max_tokens → TRUNCATED, refusal → REFUSED, anything else → FAILED
 * </pre>
 *
 * <p>The history is append-only: Claude 5.5 models bind thinking blocks to the exact conversation
 * that produced them, so an edited history is rejected. Trimming context is M3's job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final LlmGateway llm;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final SystemPrompt systemPrompt;
    private final CostCalculator costCalculator;
    private final RunRecorder recorder;
    private final AnthropicProperties anthropic;
    private final AgentLimitsProperties limits;
    private final Clock clock;

    public AgentRunResult run(String question) {
        Run run = new Run(UUID.randomUUID(), clock.instant(), ToolContext.newRun());
        recorder.runStarted(run.id, question, anthropic.agentModel(), run.startedAt);
        run.append(MessageParam.builder().role(MessageParam.Role.USER).content(question).build());

        while (true) {
            Optional<AgentRunResult> limitHit = checkLimits(run);
            if (limitHit.isPresent()) {
                return limitHit.get();
            }

            run.turns++;
            Instant callStart = clock.instant();
            Message response;
            try {
                response = llm.send(request(run.messages));
            } catch (AnthropicException e) {
                return run.finish(RunStatus.FAILED, "model call failed: " + describe(e), null);
            }
            long latencyMs = Duration.between(callStart, clock.instant()).toMillis();

            TokenUsage usage = TokenUsage.of(response.usage());
            BigDecimal cost = costCalculator.cost(anthropic.agentModel(), usage);
            run.usage = run.usage.plus(usage);
            run.cost = run.cost.add(cost);
            String stopReason = response.stopReason().map(StopReason::asString).orElse("none");
            recorder.modelCall(run.id, new ModelCallStep(++run.steps, run.turns, response.model().asString(),
                    response.id(), stopReason, usage, cost, latencyMs, refusalDetail(response).orElse(null)));
            run.append(response.toParam());

            StopReason reason = response.stopReason().orElse(null);
            if (StopReason.TOOL_USE.equals(reason)) {
                run.append(executeTools(run, response));
            } else if (StopReason.END_TURN.equals(reason)) {
                return run.finish(RunStatus.COMPLETED, null, text(response));
            } else if (StopReason.MAX_TOKENS.equals(reason)) {
                return run.finish(RunStatus.TRUNCATED, "maxTokens=" + anthropic.agentMaxTokens(), text(response));
            } else if (StopReason.REFUSAL.equals(reason)) {
                return run.finish(RunStatus.REFUSED, refusalDetail(response).orElse("category=unknown"), null);
            } else {
                return run.finish(RunStatus.FAILED, "unexpected stop_reason=" + stopReason, null);
            }
        }
    }

    /**
     * Checked before every model call. A single call can still overshoot the token or cost budget;
     * the next one is then refused. A slow call can overshoot the timeout the same way.
     */
    private Optional<AgentRunResult> checkLimits(Run run) {
        if (run.turns >= limits.maxTurns()) {
            return Optional.of(run.finish(RunStatus.MAX_TURNS_REACHED, "maxTurns=" + limits.maxTurns(), null));
        }
        Duration elapsed = Duration.between(run.startedAt, clock.instant());
        if (elapsed.compareTo(limits.runTimeout()) >= 0) {
            return Optional.of(run.finish(RunStatus.TIMED_OUT, "runTimeout=" + limits.runTimeout(), null));
        }
        if (run.usage.total() >= limits.maxTotalTokens()) {
            return Optional.of(run.finish(RunStatus.BUDGET_EXCEEDED,
                    "tokens=" + run.usage.total() + " maxTotalTokens=" + limits.maxTotalTokens(), null));
        }
        if (run.cost.compareTo(limits.maxCostUsd()) >= 0) {
            return Optional.of(run.finish(RunStatus.BUDGET_EXCEEDED,
                    "costUsd=" + run.cost + " maxCostUsd=" + limits.maxCostUsd(), null));
        }
        return Optional.empty();
    }

    private MessageCreateParams request(List<MessageParam> messages) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(anthropic.agentModel())
                .maxTokens(anthropic.agentMaxTokens())
                // Thinking is always on for Sonnet 5.5; effort controls how much (and what it costs)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(anthropic.agentEffort())).build())
                .system(systemPrompt.text())
                .messages(messages);
        toolRegistry.definitions().forEach(builder::addTool);
        return builder.build();
    }

    /**
     * Runs every {@code tool_use} block of one response. All results go back in a single user
     * message, each matched to its call by {@code tool_use_id}; a missing result is an API error.
     */
    private MessageParam executeTools(Run run, Message response) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            Optional<ToolUseBlock> toolUse = block.toolUse();
            if (toolUse.isEmpty()) {
                continue;
            }
            ToolUseBlock call = toolUse.get();
            Map<String, Object> input = toolInput(call);
            ToolExecutor.Execution execution = toolExecutor.execute(call.name(), input, run.toolContext);
            ToolResult result = execution.result();
            recorder.toolCall(run.id, new ToolCallStep(++run.steps, run.turns, call.id(), call.name(),
                    json(input), result.error(), result.content().length(), execution.latencyMs()));
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(result.content())
                    .isError(result.error())
                    .build()));
        }
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolInput(ToolUseBlock call) {
        Map<String, Object> input = call._input().convert(Map.class);
        return input == null ? Map.of() : input;
    }

    private static String text(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }

    private static Optional<String> refusalDetail(Message response) {
        return response.stopDetails()
                .flatMap(details -> details.category())
                .map(category -> "category=" + category.asString());
    }

    /** Status code and error type only: API error messages can echo request content. */
    private static String describe(AnthropicException e) {
        if (e instanceof AnthropicServiceException service) {
            return "HTTP " + service.statusCode() + service.errorType().map(t -> " " + t).orElse("");
        }
        return e.getClass().getSimpleName();
    }

    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Mutable state of one run. Lives only for the duration of {@link #run}; the recorder has the durable copy. */
    private final class Run {
        final UUID id;
        final Instant startedAt;
        final ToolContext toolContext;
        final List<MessageParam> messages = new ArrayList<>();
        int turns;
        int steps;
        TokenUsage usage = TokenUsage.ZERO;
        BigDecimal cost = BigDecimal.ZERO;

        Run(UUID id, Instant startedAt, ToolContext toolContext) {
            this.id = id;
            this.startedAt = startedAt;
            this.toolContext = toolContext;
        }

        void append(MessageParam message) {
            messages.add(message);
            recorder.messageAppended(id, messages.size(), message);
        }

        AgentRunResult finish(RunStatus status, String detail, String answer) {
            CitationChecker.Report citations = CitationChecker.check(answer, toolContext.sources());
            AgentRunResult result = new AgentRunResult(id, status, detail, answer, citations.cited(),
                    citations.invented(), toolContext.sources().all(), turns, usage, cost);
            recorder.runFinished(id, result, clock.instant());
            log.info("run finished runId={} status={} turns={} tokens={} costUsd={} citations={} invented={}",
                    id, status, turns, usage.total(), cost, citations.cited().size(), citations.invented().size());
            return result;
        }
    }
}
