package com.tw.docsagent.trace;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads runs and their traces back. */
@Repository
@RequiredArgsConstructor
public class RunTraceRepository {

    private final JdbcClient jdbc;

    public Optional<RunTrace> find(UUID runId) {
        List<RunTrace.Step> steps = jdbc.sql("select * from agent_step where run_id = :id order by step_no")
                .param("id", runId)
                .query((rs, i) -> step(rs))
                .list();
        return jdbc.sql("select * from agent_run where id = :id")
                .param("id", runId)
                .query((rs, i) -> run(rs, steps))
                .optional();
    }

    private static RunTrace run(ResultSet rs, List<RunTrace.Step> steps) throws SQLException {
        return new RunTrace(
                rs.getObject("id", UUID.class),
                rs.getString("question"),
                rs.getString("model"),
                rs.getString("status"),
                rs.getString("status_detail"),
                rs.getString("answer"),
                fromJson(rs.getString("citations"), new TypeReference<>() { }),
                fromJson(rs.getString("invented_citations"), new TypeReference<>() { }),
                rs.getInt("turns"),
                rs.getLong("input_tokens"),
                rs.getLong("output_tokens"),
                rs.getLong("cache_write_tokens"),
                rs.getLong("cache_read_tokens"),
                rs.getBigDecimal("cost_usd"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("pending_question"),
                steps);
    }

    private static RunTrace.Step step(ResultSet rs) throws SQLException {
        return new RunTrace.Step(
                rs.getInt("step_no"),
                rs.getInt("turn"),
                rs.getString("type"),
                rs.getString("model"),
                rs.getString("stop_reason"),
                rs.getObject("input_tokens", Long.class),
                rs.getObject("output_tokens", Long.class),
                rs.getObject("cache_write_tokens", Long.class),
                rs.getObject("cache_read_tokens", Long.class),
                rs.getBigDecimal("cost_usd"),
                rs.getString("detail"),
                rs.getString("tool_name"),
                fromJson(rs.getString("tool_input"), new TypeReference<>() { }),
                rs.getObject("tool_error", Boolean.class),
                rs.getObject("tool_output_chars", Integer.class),
                rs.getLong("latency_ms"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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
