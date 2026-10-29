package com.tw.docsagent.agent;

import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.tw.docsagent.config.AgentLimitsProperties;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.llm.LlmGateway;
import com.tw.docsagent.tool.AskUserTool;
import com.tw.docsagent.tool.ToolContext;
import com.tw.docsagent.tool.ToolExecutor;
import com.tw.docsagent.tool.ToolRegistry;
import com.tw.docsagent.tool.ToolResult;
import com.tw.docsagent.trace.ModelCallStep;
import com.tw.docsagent.trace.RunRecorder;
import com.tw.docsagent.trace.StoredRun;
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

    /** 5-minute ephemeral prompt cache (the default TTL). */
    private static final CacheControlEphemeral CACHE = CacheControlEphemeral.builder().build();

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
        UUID id = UUID.randomUUID();
        Instant startedAt = clock.instant();
        recorder.runStarted(id, question, anthropic.agentModel(), startedAt);
        Run run = new Run(id, ToolContext.newRun(), new ArrayList<>(), 0, 0, TokenUsage.ZERO, BigDecimal.ZERO,
                Duration.ZERO, startedAt);
        run.append(MessageParam.builder().role(MessageParam.Role.USER).content(question).build());
        return loop(run);
    }

    /**
     * Continues a run that paused on askUser: the answer becomes the tool_result of that call, and the
     * loop goes on with the same history, budgets and citations. The run is claimed first, so two
     * answers for the same question can't both run.
     *
     * @throws NoSuchRunException      if there is no such run
     * @throws RunNotWaitingException  if the run is not waiting, or another request claimed it first
     */
    public AgentRunResult resume(UUID runId, String answer) {
        StoredRun stored = recorder.load(runId).orElseThrow(() -> new NoSuchRunException(runId));
        if (stored.status() != RunStatus.WAITING_FOR_USER) {
            throw new RunNotWaitingException(runId);
        }
        Instant resumedAt = clock.instant();
        if (!recorder.claimForResume(runId, stored.version(), resumedAt)) {
            throw new RunNotWaitingException(runId);
        }
        Run run = new Run(runId, ToolContext.restore(stored.sources()), new ArrayList<>(stored.messages()),
                stored.turns(), stored.lastStepNo(), stored.usage(), stored.costUsd(),
                Duration.ofMillis(stored.activeMs()), resumedAt);
        run.append(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.of(
                ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(stored.pendingToolUseId())
                        .content(answer)
                        .build()))).build());
        log.info("run resumed runId={} turns={}", runId, stored.turns());
        return loop(run);
    }

    private AgentRunResult loop(Run run) {
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
                List<ToolUseBlock> calls = toolUses(response);
                Optional<String> question = askUserAlone(calls);
                if (question.isPresent()) {
                    return run.pause(calls.getFirst(), question.get());
                }
                run.append(executeTools(run, calls));
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
     * The question if this turn is a single, valid askUser call (the run pauses on it). Anything else
     * that names askUser is answered with an error result instead, so Claude can ask again alone.
     */
    private static Optional<String> askUserAlone(List<ToolUseBlock> calls) {
        if (calls.size() != 1 || !AskUserTool.NAME.equals(calls.getFirst().name())) {
            return Optional.empty();
        }
        return validQuestion(toolInput(calls.getFirst()));
    }

    private static Optional<String> validQuestion(Map<String, Object> input) {
        Object question = input.get("question");
        if (!(question instanceof String text) || text.isBlank() || text.strip().length() > AskUserTool.MAX_QUESTION_CHARS) {
            return Optional.empty();
        }
        return Optional.of(text.strip());
    }

    /**
     * Checked before every model call. A single call can still overshoot the token or cost budget;
     * the next one is then refused. A slow call can overshoot the timeout the same way.
     */
    private Optional<AgentRunResult> checkLimits(Run run) {
        if (run.turns >= limits.maxTurns()) {
            return Optional.of(run.finish(RunStatus.MAX_TURNS_REACHED, "maxTurns=" + limits.maxTurns(), null));
        }
        Duration elapsed = run.activeTime(clock.instant());
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
                .system(MessageCreateParams.System.ofTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt.text())
                        .cacheControl(CACHE)
                        .build())))
                .messages(withCacheBreakpoint(messages));
        toolRegistry.definitions().forEach(builder::addTool);
        return builder.build();
    }

    /**
     * Prompt caching: a cache breakpoint on the newest tool result caches everything before it
     * (system prompt, tools, the whole history) for the next call. Two breakpoints are used in
     * total (system + here); the API allows four.
     *
     * <p>The breakpoint goes on a copy that is sent to the API only. The stored history stays
     * unchanged, so the thinking blocks still match what Claude produced.
     */
    static List<MessageParam> withCacheBreakpoint(List<MessageParam> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        MessageParam last = messages.getLast();
        List<ContentBlockParam> blocks = last.content().blockParams().orElse(List.of());
        if (blocks.isEmpty()) {
            return messages;
        }
        int lastIndex = blocks.size() - 1;
        Optional<ToolResultBlockParam> toolResult = blocks.get(lastIndex).toolResult();
        if (toolResult.isEmpty()) {
            return messages;
        }
        List<ContentBlockParam> marked = new ArrayList<>(blocks);
        marked.set(lastIndex, ContentBlockParam.ofToolResult(
                toolResult.get().toBuilder().cacheControl(CACHE).build()));
        List<MessageParam> copy = new ArrayList<>(messages.subList(0, messages.size() - 1));
        copy.add(last.toBuilder().contentOfBlockParams(marked).build());
        return copy;
    }

    /**
     * Runs every {@code tool_use} block of one response. All results go back in a single user
     * message, each matched to its call by {@code tool_use_id}; a missing result is an API error.
     * askUser never runs here: when it can't pause the run, it gets an error result that says why.
     */
    private MessageParam executeTools(Run run, List<ToolUseBlock> calls) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            Map<String, Object> input = toolInput(call);
            ToolResult result;
            long latencyMs = 0;
            if (AskUserTool.NAME.equals(call.name())) {
                result = ToolResult.error(calls.size() > 1
                        ? "askUser must be the only tool call in its turn. Call it alone, without searchDocs."
                        : "askUser needs one question of at most " + AskUserTool.MAX_QUESTION_CHARS + " characters.");
            } else {
                ToolExecutor.Execution execution = toolExecutor.execute(call.name(), input, run.toolContext);
                result = execution.result();
                latencyMs = execution.latencyMs();
            }
            recorder.toolCall(run.id, new ToolCallStep(++run.steps, run.turns, call.id(), call.name(),
                    json(input), result.error(), result.content().length(), latencyMs));
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(result.content())
                    .isError(result.error())
                    .build()));
        }
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build();
    }

    private static List<ToolUseBlock> toolUses(Message response) {
        return response.content().stream()
                .flatMap(block -> block.toolUse().stream())
                .toList();
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
                .flatMap(RefusalStopDetails::category)
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

    /**
     * Mutable state of one run. Lives while the loop works on it; between requests the recorder has
     * the durable copy, and a resume rebuilds this object from it.
     */
    private final class Run {
        final UUID id;
        final ToolContext toolContext;
        final List<MessageParam> messages;
        int turns;
        int steps;
        TokenUsage usage;
        BigDecimal cost;
        /** Active time before the current segment (paused time is not counted). */
        final Duration activeBefore;
        /** Start of the current active segment: the run start, or the last resume. */
        final Instant segmentStart;

        Run(UUID id, ToolContext toolContext, List<MessageParam> messages, int turns, int steps, TokenUsage usage,
            BigDecimal cost, Duration activeBefore, Instant segmentStart) {
            this.id = id;
            this.toolContext = toolContext;
            this.messages = messages;
            this.turns = turns;
            this.steps = steps;
            this.usage = usage;
            this.cost = cost;
            this.activeBefore = activeBefore;
            this.segmentStart = segmentStart;
        }

        Duration activeTime(Instant now) {
            return activeBefore.plus(Duration.between(segmentStart, now));
        }

        void append(MessageParam message) {
            messages.add(message);
            recorder.messageAppended(id, messages.size(), message);
        }

        AgentRunResult pause(ToolUseBlock call, String question) {
            Instant now = clock.instant();
            recorder.toolCall(id, new ToolCallStep(++steps, turns, call.id(), call.name(),
                    json(toolInput(call)), false, 0, 0));
            recorder.runPaused(id, new Pause(call.id(), question, turns, usage, cost, activeTime(now),
                    toolContext.sources().all()));
            log.info("run waiting for user runId={} turns={} tokens={} costUsd={}", id, turns, usage.total(), cost);
            return result(RunStatus.WAITING_FOR_USER, null, null, question);
        }

        AgentRunResult finish(RunStatus status, String detail, String answer) {
            AgentRunResult result = result(status, detail, answer, null);
            recorder.runFinished(id, result, clock.instant());
            log.info("run finished runId={} status={} turns={} tokens={} costUsd={} citations={} invented={}",
                    id, status, turns, usage.total(), cost, result.citations().size(),
                    result.inventedCitations().size());
            return result;
        }

        private AgentRunResult result(RunStatus status, String detail, String answer, String pendingQuestion) {
            CitationChecker.Report citations = CitationChecker.check(answer, toolContext.sources());
            return new AgentRunResult(id, status, detail, answer, citations.cited(), citations.invented(),
                    toolContext.sources().all(), turns, usage, cost, pendingQuestion);
        }
    }
}
