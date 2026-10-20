package com.tw.docsagent.eval;

import java.util.List;

/**
 * One question of the golden set ({@code src/test/resources/evals/golden-set.yaml}).
 *
 * @param expectedSources section anchors that contain the answer; retrieval is a hit when
 *                        {@code searchDocs} returned at least one of them. Empty when {@code expect} is DECLINE
 * @param keyPoints       facts a correct answer must contain (or, for DECLINE, the expected behaviour); checked by the judge
 * @param mustNotSay      statements that make an answer wrong, e.g. a Boot 3 package name
 */
public record GoldenQuestion(
        String id,
        Category category,
        Expect expect,
        String question,
        List<String> expectedSources,
        List<String> keyPoints,
        List<String> mustNotSay) {

    public enum Category { HOW_TO, CONCEPT, CONFIGURATION, VERSION_SPECIFIC, OUT_OF_SCOPE, PROMPT_INJECTION }

    /** ANSWER: the docs cover it. DECLINE: they don't, and the agent should say so instead of answering from memory. */
    public enum Expect { ANSWER, DECLINE }
}
