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

        // Notes about the run itself; judged() adds the judge's part
        String runNote = null;
        if (!answerQuestion && run.status() == RunStatus.REFUSED) {
            runNote = "refused (" + run.statusDetail() + "), counted as a decline";
        } else if (run.status() != RunStatus.COMPLETED) {
            runNote = "run ended " + run.status() + (run.statusDetail() == null ? "" : " (" + run.statusDetail() + ")");
        }

        QuestionResult unjudged = new QuestionResult(question.id(), question.category(), question.expect(),
                question.traits(), false, run.status(), retrievalHit, passageHit, citedExpected, 0,
                question.keyPoints().size(), List.of(), List.of(), List.of(), 0, run.inventedCitations(), retrieved,
                cited, retrieval.searchQueries(), chunkIds, run.turns(), latencyMs, run.costUsd(), BigDecimal.ZERO,
                run.runId(), runNote, run.answer());
        return judged(unjudged, question, judgement, judgeError);
    }

    /**
     * Applies the judge's decisions to a result: the checklist fields, the pass rules and the note.
     * {@link #score} uses it for a fresh run; {@code RejudgeIT} uses it on a result read back from a
     * report, so a stored answer can be judged again without running the agent. Retrieval fields are
     * kept as they are.
     *
     * @param scored     a result whose note, if any, is about the run itself (set by {@link #score});
     *                   for a COMPLETED run the note is recomputed here
     * @param judgement  null when the judge didn't run (no completed answer) or failed
     * @param judgeError why the judge failed, or null
     */
    public static QuestionResult judged(QuestionResult scored, GoldenQuestion question, Judgement judgement,
                                        String judgeError) {
        boolean answerQuestion = question.expect() == Expect.ANSWER;
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

        boolean completed = scored.status() == RunStatus.COMPLETED;
        boolean checklistPassed = completed
                && judgement != null
                && met == question.keyPoints().size()
                && violations.isEmpty()
                && scored.inventedCitations().isEmpty();
        boolean refusedDecline = !answerQuestion && scored.status() == RunStatus.REFUSED;
        boolean passed = checklistPassed || refusedDecline;

        String note = scored.note();
        if (completed) {
            note = judgeError != null ? "judge failed: " + judgeError
                    : !scored.inventedCitations().isEmpty() ? "invented citations " + scored.inventedCitations()
                    : null;
        }

        return new QuestionResult(question.id(), question.category(), question.expect(), question.traits(), passed,
                scored.status(), scored.retrievalHit(), scored.passageHit(), scored.citedExpected(), met,
                question.keyPoints().size(), List.copyOf(missed), List.copyOf(violations), List.copyOf(uncertain),
                judgement == null ? 0 : judgement.rounds(), scored.inventedCitations(), scored.retrievedAnchors(),
                scored.citedAnchors(), scored.searchQueries(), scored.retrievedChunkIds(), scored.turns(),
                scored.latencyMs(), scored.agentCostUsd(), judgement == null ? BigDecimal.ZERO : judgement.costUsd(),
                scored.runId(), note, scored.answer());
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
