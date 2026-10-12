package com.tw.docsagent.trace;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.RunStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes the trace with plain SQL. Each call is its own short transaction (auto-commit), so the
 * trace of a run that dies halfway is still there.
 */
@Component
@RequiredArgsConstructor
class JdbcRunRecorder implements RunRecorder {

    private final JdbcClient jdbc;

    @Override
    public void runStarted(UUID runId, String question, String model, Instant startedAt) {
        jdbc.sql("""
                        insert into agent_run (id, question, model, status, started_at)
                        values (:id, :question, :model, :status, :startedAt)""")
                .param("id", runId)
                .param("question", question)
                .param("model", model)
                .param("status", RunStatus.RUNNING.name())
                .param("startedAt", Timestamp.from(startedAt))
                .update();
    }

    @Override
    public void messageAppended(UUID runId, int seq, MessageParam message) {
        jdbc.sql("""
                        insert into agent_message (run_id, seq, role, content)
                        values (:runId, :seq, :role, cast(:content as jsonb))""")
                .param("runId", runId)
                .param("seq", seq)
                .param("role", message.role().asString())
                .param("content", json(message))
                .update();
    }

    @Override
    public void modelCall(UUID runId, ModelCallStep step) {
        jdbc.sql("""
                        insert into agent_step (run_id, step_no, turn, type, model, message_id, stop_reason,
                                                input_tokens, output_tokens, cache_write_tokens, cache_read_tokens,
                                                cost_usd, detail, latency_ms)
                        values (:runId, :stepNo, :turn, 'MODEL_CALL', :model, :messageId, :stopReason,
                                :input, :output, :cacheWrite, :cacheRead, :cost, :detail, :latencyMs)""")
                .param("runId", runId)
                .param("stepNo", step.stepNo())
                .param("turn", step.turn())
                .param("model", step.model())
                .param("messageId", step.messageId())
                .param("stopReason", step.stopReason())
                .param("input", step.usage().input())
                .param("output", step.usage().output())
                .param("cacheWrite", step.usage().cacheWrite())
                .param("cacheRead", step.usage().cacheRead())
                .param("cost", step.costUsd())
                .param("detail", step.detail())
                .param("latencyMs", step.latencyMs())
                .update();
    }

    @Override
    public void toolCall(UUID runId, ToolCallStep step) {
        jdbc.sql("""
                        insert into agent_step (run_id, step_no, turn, type, tool_use_id, tool_name, tool_input,
                                                tool_error, tool_output_chars, latency_ms)
                        values (:runId, :stepNo, :turn, 'TOOL_CALL', :toolUseId, :toolName, cast(:input as jsonb),
                                :error, :outputChars, :latencyMs)""")
                .param("runId", runId)
                .param("stepNo", step.stepNo())
                .param("turn", step.turn())
                .param("toolUseId", step.toolUseId())
                .param("toolName", step.toolName())
                .param("input", step.inputJson())
                .param("error", step.error())
                .param("outputChars", step.outputChars())
                .param("latencyMs", step.latencyMs())
                .update();
    }

    @Override
    public void runFinished(UUID runId, AgentRunResult result, Instant finishedAt) {
        jdbc.sql("""
                        update agent_run
                        set status = :status, status_detail = :detail, answer = :answer,
                            citations = cast(:citations as jsonb), invented_citations = cast(:invented as jsonb),
                            turns = :turns, input_tokens = :input, output_tokens = :output,
                            cache_write_tokens = :cacheWrite, cache_read_tokens = :cacheRead,
                            cost_usd = :cost, finished_at = :finishedAt, version = version + 1
                        where id = :id""")
                .param("id", runId)
                .param("status", result.status().name())
                .param("detail", result.statusDetail())
                .param("answer", result.answer())
                .param("citations", json(result.citations()))
                .param("invented", json(result.inventedCitations()))
                .param("turns", result.turns())
                .param("input", result.usage().input())
                .param("output", result.usage().output())
                .param("cacheWrite", result.usage().cacheWrite())
                .param("cacheRead", result.usage().cacheRead())
                .param("cost", result.costUsd())
                .param("finishedAt", Timestamp.from(finishedAt))
                .update();
    }

    /** The SDK's own mapper, so SDK types serialize exactly as they go over the wire. */
    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }
}
