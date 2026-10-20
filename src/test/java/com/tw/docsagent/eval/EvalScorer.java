package com.tw.docsagent.eval;

import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.eval.AnswerJudge.Check;
import com.tw.docsagent.eval.AnswerJudge.Judgement;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.tool.SourceCatalog.Source;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one agent run (and the judge's checklist decisions, if any) into a {@link QuestionResult}.
 * Pure code, no model calls, so every rule below is unit-tested.
 *
 * <p>Pass rules:
 * <ul>
 *   <li><b>answer</b>: run COMPLETED, no invented citations, every key point present, no must-not-say said.
 *       Retrieval is measured separately and doesn't decide the pass: a correct answer from a missed
 *       retrieval is still worth seeing as "passed but retrieval missed".</li>
 *   <li><b>decline</b>: the same checklist rules (the key points describe the declining behaviour),
 *       or the run ended REFUSED, which is also a way of not answering.</li>
 * </ul>
 */
public final class EvalScorer {

    private EvalScorer() {
    }

    /**
     * @param judgement  null when the judge didn't run (no completed answer) or failed
     * @param judgeError why the judge failed, or null
     */
    public static QuestionResult score(GoldenQuestion question, AgentRunResult run, long latencyMs,
                                       Judgement judgement, String judgeError) {
        List<String> retrieved = anchors(run.retrieved());
        List<String> cited = anchors(run.citations());
        boolean answerQuestion = question.expect() == Expect.ANSWER;
        Boolean retrievalHit = answerQuestion ? containsAny(retrieved, question.expectedSources()) : null;
        Boolean citedExpected = answerQuestion ? containsAny(cited, question.expectedSources()) : null;

        List<String> missed = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        int met = 0;
        if (judgement != null) {
            for (Check check : judgement.keyPoints()) {
                if (check.present()) {
                    met++;
                } else {
                    missed.add(question.keyPoints().get(check.index() - 1) + " (judge: " + check.reason() + ")");
                }
            }
            for (Check check : judgement.mustNotSay()) {
                if (check.present()) {
                    violations.add(question.mustNotSay().get(check.index() - 1) + " (judge: " + check.reason() + ")");
                }
            }
        }

        boolean checklistPassed = run.status() == RunStatus.COMPLETED
                && judgement != null
                && met == question.keyPoints().size()
                && violations.isEmpty()
                && run.inventedCitations().isEmpty();
        boolean refusedDecline = !answerQuestion && run.status() == RunStatus.REFUSED;
        boolean passed = checklistPassed || refusedDecline;

        String note = null;
        if (refusedDecline) {
            note = "refused (" + run.statusDetail() + "), counted as a decline";
        } else if (run.status() != RunStatus.COMPLETED) {
            note = "run ended " + run.status() + (run.statusDetail() == null ? "" : " (" + run.statusDetail() + ")");
        } else if (judgeError != null) {
            note = "judge failed: " + judgeError;
        } else if (!run.inventedCitations().isEmpty()) {
            note = "invented citations " + run.inventedCitations();
        }

        return new QuestionResult(question.id(), question.category(), question.expect(), passed, run.status(),
                retrievalHit, citedExpected, met, question.keyPoints().size(), List.copyOf(missed),
                List.copyOf(violations), run.inventedCitations(), retrieved, cited, run.turns(), latencyMs,
                run.costUsd(), judgement == null ? BigDecimal.ZERO : judgement.costUsd(), run.runId(), note,
                run.answer());
    }

    /** Citation URLs end in {@code #anchor}, e.g. {@code …/testcontainers.html#testing.testcontainers.service-connections}. */
    static String anchor(String url) {
        int hash = url.lastIndexOf('#');
        return hash >= 0 ? url.substring(hash + 1) : url;
    }

    private static List<String> anchors(List<Source> sources) {
        return sources.stream().map(source -> anchor(source.url())).distinct().toList();
    }

    private static boolean containsAny(List<String> anchors, List<String> expected) {
        return expected.stream().anyMatch(anchors::contains);
    }
}
