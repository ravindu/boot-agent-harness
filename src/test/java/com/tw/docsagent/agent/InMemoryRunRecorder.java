package com.tw.docsagent.agent;

import com.anthropic.models.messages.MessageParam;
import com.tw.docsagent.trace.ModelCallStep;
import com.tw.docsagent.trace.RunRecorder;
import com.tw.docsagent.trace.StoredRun;
import com.tw.docsagent.trace.ToolCallStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Keeps the trace in lists so tests can assert on it. Holds one run, which is all the loop tests need. */
class InMemoryRunRecorder implements RunRecorder {

    final List<MessageParam> messages = new ArrayList<>();
    final List<Object> steps = new ArrayList<>();
    AgentRunResult finished;
    Pause paused;

    private UUID runId;
    private RunStatus status;
    private long version;

    @Override
    public void runStarted(UUID runId, String question, String model, Instant startedAt) {
        this.runId = runId;
        this.status = RunStatus.RUNNING;
    }

    @Override
    public void messageAppended(UUID runId, int seq, MessageParam message) {
        if (seq != messages.size() + 1) {
            throw new AssertionError("message seq " + seq + " out of order");
        }
        messages.add(message);
    }

    @Override
    public void modelCall(UUID runId, ModelCallStep step) {
        steps.add(step);
    }

    @Override
    public void toolCall(UUID runId, ToolCallStep step) {
        steps.add(step);
    }

    @Override
    public void runFinished(UUID runId, AgentRunResult result, Instant finishedAt) {
        finished = result;
        status = result.status();
        version++;
    }

    @Override
    public void runPaused(UUID runId, Pause pause) {
        paused = pause;
        status = RunStatus.WAITING_FOR_USER;
        version++;
    }

    @Override
    public Optional<StoredRun> load(UUID runId) {
        if (status == null || !runId.equals(this.runId)) {
            return Optional.empty();
        }
        int lastStep = steps.stream().mapToInt(step -> step instanceof ModelCallStep m ? m.stepNo()
                : ((ToolCallStep) step).stepNo()).max().orElse(0);
        Pause pause = paused;
        return Optional.of(new StoredRun(runId, status, version,
                pause == null ? 0 : pause.turns(),
                pause == null ? TokenUsage.ZERO : pause.usage(),
                pause == null ? java.math.BigDecimal.ZERO : pause.costUsd(),
                pause == null ? 0 : pause.active().toMillis(),
                pause == null ? null : pause.toolUseId(),
                pause == null ? null : pause.question(),
                pause == null ? List.of() : pause.sources(),
                lastStep,
                List.copyOf(messages)));
    }

    @Override
    public boolean claimForResume(UUID runId, long version, Instant resumedAt) {
        if (status != RunStatus.WAITING_FOR_USER || this.version != version) {
            return false;
        }
        status = RunStatus.RUNNING;
        this.version++;
        return true;
    }
}
