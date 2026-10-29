package com.tw.docsagent.trace;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.Pause;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.agent.TokenUsage;
import com.tw.docsagent.tool.SourceCatalog.Source;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the trace with plain SQL. Each call is its own short transaction (auto-commit), so the
 * trace of a run that dies halfway is still there.
 */
@Component
@RequiredArgsConstructor
class JdbcRunRecorder implements RunRecorder {

    private static final JsonMapper PLAIN_JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    @Override
    public void runStarted(UUID runId, String question, String model, Instant startedAt) {
        jdbc.sql("""
                        insert into agent_run (id, question, model, status, started_at, segment_started_at)
                        values (:id, :question, :model, :status, :startedAt, :startedAt)""")
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
                .param("citations", plainJson(result.citations()))
                .param("invented", plainJson(result.inventedCitations()))
                .param("turns", result.turns())
                .param("input", result.usage().input())
                .param("output", result.usage().output())
                .param("cacheWrite", result.usage().cacheWrite())
                .param("cacheRead", result.usage().cacheRead())
                .param("cost", result.costUsd())
                .param("finishedAt", Timestamp.from(finishedAt))
                .update();
    }

    @Override
    public void runPaused(UUID runId, Pause pause) {
        jdbc.sql("""
                        update agent_run
                        set status = :status, pending_tool_use_id = :toolUseId, pending_question = :question,
                            turns = :turns, input_tokens = :input, output_tokens = :output,
                            cache_write_tokens = :cacheWrite, cache_read_tokens = :cacheRead,
                            cost_usd = :cost, sources = cast(:sources as jsonb), active_ms = :activeMs,
                            segment_started_at = null, version = version + 1
                        where id = :id""")
                .param("id", runId)
                .param("status", RunStatus.WAITING_FOR_USER.name())
                .param("toolUseId", pause.toolUseId())
                .param("question", pause.question())
                .param("turns", pause.turns())
                .param("input", pause.usage().input())
                .param("output", pause.usage().output())
                .param("cacheWrite", pause.usage().cacheWrite())
                .param("cacheRead", pause.usage().cacheRead())
                .param("cost", pause.costUsd())
                .param("sources", plainJson(pause.sources()))
                .param("activeMs", pause.active().toMillis())
                .update();
    }

    @Override
    public Optional<StoredRun> load(UUID runId) {
        return jdbc.sql("""
                        select id, status, version, turns, input_tokens, output_tokens, cache_write_tokens,
                               cache_read_tokens, cost_usd, active_ms, pending_tool_use_id, pending_question, sources
                        from agent_run where id = :id""")
                .param("id", runId)
                .query((rs, i) -> new StoredRun(
                        rs.getObject("id", UUID.class),
                        RunStatus.valueOf(rs.getString("status")),
                        rs.getLong("version"),
                        rs.getInt("turns"),
                        new TokenUsage(rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                                rs.getLong("cache_write_tokens"), rs.getLong("cache_read_tokens")),
                        rs.getBigDecimal("cost_usd"),
                        rs.getLong("active_ms"),
                        rs.getString("pending_tool_use_id"),
                        rs.getString("pending_question"),
                        plainFromJson(rs.getString("sources"), new tools.jackson.core.type.TypeReference<List<Source>>() { }),
                        lastStepNo(runId),
                        messages(runId)))
                .optional();
    }

    @Override
    public boolean claimForResume(UUID runId, long version, Instant resumedAt) {
        int rows = jdbc.sql("""
                        update agent_run
                        set status = :running, pending_tool_use_id = null, pending_question = null,
                            segment_started_at = :resumedAt, version = version + 1
                        where id = :id and version = :version and status = :waiting""")
                .param("id", runId)
                .param("version", version)
                .param("running", RunStatus.RUNNING.name())
                .param("waiting", RunStatus.WAITING_FOR_USER.name())
                .param("resumedAt", Timestamp.from(resumedAt))
                .update();
        return rows == 1;
    }

    private int lastStepNo(UUID runId) {
        return jdbc.sql("select coalesce(max(step_no), 0) from agent_step where run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single();
    }

    private List<MessageParam> messages(UUID runId) {
        return jdbc.sql("select content::text from agent_message where run_id = :id order by seq")
                .param("id", runId)
                .query(String.class)
                .list()
                .stream()
                .map(json -> fromJson(json, MessageParam.class))
                .toList();
    }

    private static String json(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /**
     * For records such as {@link Source}. The SDK mapper writes them as {@code {}} because it doesn't see
     * record accessors, so they go through Spring's Jackson 3 mapper, which does.
     */
    private static String plainJson(Object value) {
        return PLAIN_JSON.writeValueAsString(value);
    }

    private static <T> T plainFromJson(String json, tools.jackson.core.type.TypeReference<T> type) {
        return PLAIN_JSON.readValue(json, type);
    }

    private static <T> T fromJson(String json, Class<T> type) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read " + type.getSimpleName() + " from the trace", e);
        }
    }

    private static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON in trace", e);
        }
    }
}
