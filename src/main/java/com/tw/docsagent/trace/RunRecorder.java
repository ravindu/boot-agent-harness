package com.tw.docsagent.trace;

import com.anthropic.models.messages.MessageParam;
import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.Pause;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the agent loop keeps a run: the trace while it runs, and the state it needs to pause and
 * resume. Called synchronously after every step, so a crashed run still leaves everything up to the crash.
 */
public interface RunRecorder {

    void runStarted(UUID runId, String question, String model, Instant startedAt);

    /** Every message sent to the model, in order. Kept in full: resuming a run replays it. */
    void messageAppended(UUID runId, int seq, MessageParam message);

    void modelCall(UUID runId, ModelCallStep step);

    void toolCall(UUID runId, ToolCallStep step);

    void runFinished(UUID runId, AgentRunResult result, Instant finishedAt);

    /** The run waits for the user: totals and the pending askUser call are stored. */
    void runPaused(UUID runId, Pause pause);

    Optional<StoredRun> load(UUID runId);

    /**
     * Moves a WAITING_FOR_USER run back to RUNNING, if nobody else resumed it first.
     *
     * @return false when the version changed (someone answered already) or the run isn't waiting
     */
    boolean claimForResume(UUID runId, long version, Instant resumedAt);
}
