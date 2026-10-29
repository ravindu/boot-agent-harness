package com.tw.docsagent.trace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A stored run with its step-by-step trace, as returned by {@code GET /api/agent/runs/{id}}. */
public record RunTrace(
        UUID id,
        String question,
        String model,
        String status,
        String statusDetail,
        String answer,
        List<Map<String, Object>> citations,
        List<String> inventedCitations,
        int turns,
        long inputTokens,
        long outputTokens,
        long cacheWriteTokens,
        long cacheReadTokens,
        BigDecimal costUsd,
        Instant startedAt,
        Instant finishedAt,
        String pendingQuestion,
        List<Step> steps) {

    /** One model call or tool call. Fields of the other step type are null. */
    public record Step(
            int stepNo,
            int turn,
            String type,
            String model,
            String stopReason,
            Long inputTokens,
            Long outputTokens,
            Long cacheWriteTokens,
            Long cacheReadTokens,
            BigDecimal costUsd,
            String detail,
            String toolName,
            Map<String, Object> toolInput,
            Boolean toolError,
            Integer toolOutputChars,
            long latencyMs) {
    }
}
