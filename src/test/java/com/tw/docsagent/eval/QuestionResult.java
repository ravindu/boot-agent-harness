package com.tw.docsagent.eval;

import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.eval.GoldenQuestion.Trait;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The score of one golden question in one eval run, plus the evidence needed to diagnose it
 * without the run's trace (which lives in a throwaway Testcontainers database).
 *
 * @param traits           the question's traits, copied from the golden set so a report can be read on its own
 * @param retrievalHit     searchDocs returned at least one chunk of an expected section; null for DECLINE questions
 * @param passageHit       every expectedText phrase was in a retrieved chunk; null when the question has none
 * @param citedExpected    the answer cited at least one expected section; null for DECLINE questions
 * @param keyPointsMet     key points the judge found in the answer (0 when the judge didn't run)
 * @param missedKeyPoints  key points the judge did not find, with its reason
 * @param violations       must-not-say items the answer did say, with the judge's reason
 * @param uncertain        checklist items the judge rounds disagreed on; worth checking by hand
 * @param judgeRounds      0 (judge didn't run), 1, or 2 (round 1 failed something, second opinion asked)
 * @param searchQueries    the queries Claude sent to searchDocs, in order
 * @param retrievedChunkIds ids of every chunk shown to Claude ({@code vector_store.id})
 * @param note             why the question failed outside the checklist (run status, judge error), or null
 */
public record QuestionResult(
        String id,
        Category category,
        Expect expect,
        List<Trait> traits,
        boolean passed,
        RunStatus status,
        Boolean retrievalHit,
        Boolean passageHit,
        Boolean citedExpected,
        int keyPointsMet,
        int keyPointsTotal,
        List<String> missedKeyPoints,
        List<String> violations,
        List<String> uncertain,
        int judgeRounds,
        List<String> inventedCitations,
        List<String> retrievedAnchors,
        List<String> citedAnchors,
        List<String> searchQueries,
        List<String> retrievedChunkIds,
        int turns,
        long latencyMs,
        BigDecimal agentCostUsd,
        BigDecimal judgeCostUsd,
        UUID runId,
        String note,
        String answer) {
}
