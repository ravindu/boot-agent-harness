package com.tw.docsagent.eval;

import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The score of one golden question in one eval run.
 *
 * @param retrievalHit     searchDocs returned at least one expected anchor; null for DECLINE questions
 * @param citedExpected    the answer cited at least one expected anchor; null for DECLINE questions
 * @param keyPointsMet     key points the judge found in the answer (0 when the judge didn't run)
 * @param missedKeyPoints  key points the judge did not find, with its reason
 * @param violations       must-not-say items the answer did say, with the judge's reason
 * @param note             why the question failed outside the checklist (run status, judge error), or null
 */
public record QuestionResult(
        String id,
        Category category,
        Expect expect,
        boolean passed,
        RunStatus status,
        Boolean retrievalHit,
        Boolean citedExpected,
        int keyPointsMet,
        int keyPointsTotal,
        List<String> missedKeyPoints,
        List<String> violations,
        List<String> inventedCitations,
        List<String> retrievedAnchors,
        List<String> citedAnchors,
        int turns,
        long latencyMs,
        BigDecimal agentCostUsd,
        BigDecimal judgeCostUsd,
        UUID runId,
        String note,
        String answer) {
}
