package com.tw.docsagent.trace;

import com.anthropic.models.messages.MessageParam;
import com.tw.docsagent.agent.AgentRunResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Where the agent loop writes its trace. Called synchronously after every step, so a crashed run
 * still leaves everything up to the crash.
 */
public interface RunRecorder {

    void runStarted(UUID runId, String question, String model, Instant startedAt);

    /** Every message sent to the model, in order. Kept in full: M3 resumes runs from it. */
    void messageAppended(UUID runId, int seq, MessageParam message);

    void modelCall(UUID runId, ModelCallStep step);

    void toolCall(UUID runId, ToolCallStep step);

    void runFinished(UUID runId, AgentRunResult result, Instant finishedAt);
}
