package com.tw.docsagent.agent;

import com.tw.docsagent.tool.SourceCatalog.Source;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * @param statusDetail     why the run ended, e.g. {@code maxTurns=10} or {@code category=cyber}; null when completed normally
 * @param answer           final text; null unless COMPLETED (or partial text for TRUNCATED)
 * @param citations        sources the answer cited that were really retrieved
 * @param inventedCitations cited labels that were never retrieved (grounding failure)
 * @param retrieved        every chunk shown to Claude during the run, cited or not (the eval's retrieval hit rate uses it)
 * @param turns            model calls made
 * @param pendingQuestion  what Claude asked the user; set only when WAITING_FOR_USER
 */
public record AgentRunResult(
        UUID runId,
        RunStatus status,
        String statusDetail,
        String answer,
        List<Source> citations,
        List<String> inventedCitations,
        List<Source> retrieved,
        int turns,
        TokenUsage usage,
        BigDecimal costUsd,
        String pendingQuestion) {

    /** Every result that is not waiting for a user. */
    public AgentRunResult(UUID runId, RunStatus status, String statusDetail, String answer, List<Source> citations,
                          List<String> inventedCitations, List<Source> retrieved, int turns, TokenUsage usage,
                          BigDecimal costUsd) {
        this(runId, status, statusDetail, answer, citations, inventedCitations, retrieved, turns, usage, costUsd, null);
    }
}
