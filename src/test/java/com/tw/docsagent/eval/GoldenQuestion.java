package com.tw.docsagent.eval;

import java.util.List;

/**
 * One question of the golden set ({@code src/test/resources/evals/golden-set.yaml}).
 *
 * @param traits          what the question tests (see {@link Trait}); the report breaks results down by trait
 * @param expectedSources section anchors that contain the answer; retrieval is a hit when
 *                        {@code searchDocs} returned at least one of them. Empty when {@code expect} is DECLINE
 * @param expectedText    phrases from the chunk(s) that hold the answer; passage hit = all of them were retrieved.
 *                        Optional; finer than {@code expectedSources}, because a section can have several chunks
 * @param keyPoints       facts a correct answer must contain (or, for DECLINE, the expected behaviour); checked by the judge
 * @param mustNotSay      statements that make an answer wrong, e.g. a Boot 3 package name
 */
public record GoldenQuestion(
        String id,
        Category category,
        Expect expect,
        List<Trait> traits,
        String question,
        List<String> expectedSources,
        List<String> expectedText,
        List<String> keyPoints,
        List<String> mustNotSay) {

    public enum Category { HOW_TO, CONCEPT, CONFIGURATION, VERSION_SPECIFIC, OUT_OF_SCOPE, PROMPT_INJECTION }

    /** ANSWER: the docs cover it. DECLINE: they don't, and the agent should say so instead of answering from memory. */
    public enum Expect { ANSWER, DECLINE }

    /**
     * What a question tests. Facts about the question, not a ranking: a question can have several
     * traits or none. The first three are computed from the ingested chunks ({@link DerivedTraits})
     * and checked before an eval runs; the rest are tagged by hand, with the rules in {@link GoldenSet}.
     */
    public enum Trait {
        /** Derived: no annotation, class or property named in the question occurs in the answer chunk(s). */
        PARAPHRASED,
        /** Derived: the answer is past the first chunk of its section, or in a NOTE/TIP/WARNING paragraph. */
        DEEP_DETAIL,
        /** Derived: the answer phrase is inside a table or a code block. */
        TABLE_OR_CODE,
        /** The answer needs passages from two or more chunks (exactly when there are two or more expectedText phrases). */
        MULTI_HOP,
        /** The question states something wrong that the answer has to correct. */
        FALSE_PREMISE,
        /** A Boot 3 answer is wrong for Boot 4; a mustNotSay item names it. */
        BOOT3_HABIT,
        /** The docs answer only part of the question; a key point checks that the agent says which part is missing. */
        PARTIAL_COVERAGE,
        /** Decline about the ingested topics (Testing, Data) that the model likely knows but the docs don't contain. */
        DOCS_GAP,
        /** The ingested docs have passages on a nearby topic that don't answer the question (or answer it differently). */
        DISTRACTOR
    }
}
