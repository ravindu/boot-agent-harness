package com.tw.docsagent.agent;

import com.anthropic.models.messages.MessageParam;
import com.tw.docsagent.trace.ModelCallStep;
import com.tw.docsagent.trace.RunRecorder;
import com.tw.docsagent.trace.ToolCallStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Keeps the trace in lists so tests can assert on it. */
class InMemoryRunRecorder implements RunRecorder {

    final List<MessageParam> messages = new ArrayList<>();
    final List<Object> steps = new ArrayList<>();
    AgentRunResult finished;

    @Override
    public void runStarted(UUID runId, String question, String model, Instant startedAt) {
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
    }
}
