package com.tw.docsagent.agent;

import com.tw.docsagent.tool.SourceCatalog.Source;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * @param statusDetail why the run ended, e.g. {@code maxTurns=10} or {@code category=cyber}; null when completed normally
 * @param answer       final text; null unless COMPLETED (or partial text for TRUNCATED)
 * @param citations    sources the answer cited that were really retrieved
 * @param inventedCitations cited labels that were never retrieved (grounding failure)
 * @param turns        model calls made
 */
public record AgentRunResult(
        UUID runId,
        RunStatus status,
        String statusDetail,
        String answer,
        List<Source> citations,
        List<String> inventedCitations,
        int turns,
        TokenUsage usage,
        BigDecimal costUsd) {
}
