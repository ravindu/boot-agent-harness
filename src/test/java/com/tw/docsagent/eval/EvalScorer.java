package com.tw.docsagent.eval;

import com.tw.docsagent.agent.AgentRunResult;
import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.eval.AnswerJudge.Judgement;
import com.tw.docsagent.eval.AnswerJudge.Verdict;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.tool.SourceCatalog.Source;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p>Two retrieval levels: <em>section</em> (any chunk of an expected anchor came back) and
 * <em>passage</em> (the chunk containing the answer came back, via {@code expectedText}). The baseline
 * showed why both are needed: a section can be "hit" through its table chunk while the chunk with
 * the example is missed.
 */
public final class EvalScorer {

    /** What the run's trace and the vector store say about retrieval; the run result alone has no chunk text. */
    public record Retrieval(List<String> searchQueries, Map<String, String> chunkTextById) {
    }

    private EvalScorer() {
    }

    /**
     * @param judgement  null when the judge didn't run (no completed answer) or failed
     * @param judgeError why the judge failed, or null
     */
    public static QuestionResult score(GoldenQuestion question, AgentRunResult run, long latencyMs, Retrieval retrieval,
                                       Judgement judgement, String judgeError) {
        List<String> retrieved = anchors(run.retrieved());
        List<String> cited = anchors(run.citations());
        List<String> chunkIds = run.retrieved().stream().map(Source::chunkId).toList();
        boolean answerQuestion = question.expect() == Expect.ANSWER;
        Boolean retrievalHit = answerQuestion ? containsAny(retrieved, question.expectedSources()) : null;
        Boolean citedExpected = answerQuestion ? containsAny(cited, question.expectedSources()) : null;
        Boolean passageHit = question.expectedText().isEmpty() ? null
                : passageHit(question.expectedText(), chunkIds, retrieval.chunkTextById());

        List<String> missed = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        int met = 0;
        if (judgement != null) {
            for (Verdict verdict : judgement.keyPoints()) {
                String item = question.keyPoints().get(verdict.index() - 1);
                if (verdict.present()) {
                    met++;
                } else {
                    missed.add(item + " (judge: " + verdict.reason() + ")");
                }
                if (verdict.uncertain()) {
                    uncertain.add("key point: " + item);
                }
            }
            for (Verdict verdict : judgement.mustNotSay()) {
                String item = question.mustNotSay().get(verdict.index() - 1);
                if (verdict.present()) {
                    violations.add(item + " (judge: " + verdict.reason() + ")");
                }
                if (verdict.uncertain()) {
                    uncertain.add("must not say: " + item);
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
                retrievalHit, passageHit, citedExpected, met, question.keyPoints().size(), List.copyOf(missed),
                List.copyOf(violations), List.copyOf(uncertain), judgement == null ? 0 : judgement.rounds(),
                run.inventedCitations(), retrieved, cited, retrieval.searchQueries(), chunkIds, run.turns(), latencyMs,
                run.costUsd(), judgement == null ? BigDecimal.ZERO : judgement.costUsd(), run.runId(), note,
                run.answer());
    }

    /** Every phrase must occur in at least one retrieved chunk. */
    static boolean passageHit(List<String> phrases, List<String> chunkIds, Map<String, String> chunkTextById) {
        List<String> texts = chunkIds.stream().map(id -> normalize(chunkTextById.getOrDefault(id, ""))).toList();
        return phrases.stream().map(EvalScorer::normalize).allMatch(phrase -> texts.stream().anyMatch(t -> t.contains(phrase)));
    }

    /** Same whitespace rule as the uniqueness check in EvalRunIT: line breaks and indentation don't matter. */
    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").strip();
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
