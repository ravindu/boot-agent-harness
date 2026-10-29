package com.tw.docsagent.eval;

import com.tw.docsagent.eval.GoldenQuestion.Trait;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the traits that follow from the ingested chunks: {@code paraphrased}, {@code deep-detail}
 * and {@code table-or-code}. The golden set tags them by hand (so the YAML is readable on its own)
 * and {@link GoldenSetChecks} fails a run when a tag disagrees with what this class computes.
 * Pure code, no database.
 */
final class DerivedTraits {

    static final Set<Trait> DERIVED = EnumSet.of(Trait.PARAPHRASED, Trait.DEEP_DETAIL, Trait.TABLE_OR_CODE);

    /** One ingested chunk: its text and its position within its section (0 = the first chunk). */
    record Chunk(String text, int index) {
    }

    /**
     * What a question can name: annotations ({@code @DataJpaTest}), class names ({@code JdbcTemplate}),
     * members ({@code System.out}), methods ({@code isEqualTo}), properties ({@code spring.jpa.show-sql})
     * and constants ({@code RANDOM_PORT}).
     */
    private static final Pattern IDENTIFIER = Pattern.compile(String.join("|",
            "@\\w+",
            "\\b[A-Z]\\w*\\.\\w+",
            "\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\\w*",
            "\\b[a-z]+[A-Z]\\w*",
            "\\b[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+",
            "\\b[A-Z]+_[A-Z_]+\\b"));

    /** AsciiDoc admonitions: a fact in one of these is easy to skip, for readers and for retrieval. */
    private static final Pattern CALLOUT = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):");

    private DerivedTraits() {
    }

    /** @param answerChunks the chunk holding each expectedText phrase, in phrase order; empty for decline questions */
    static Set<Trait> derive(GoldenQuestion question, List<Chunk> answerChunks) {
        Set<Trait> traits = EnumSet.noneOf(Trait.class);
        if (answerChunks.isEmpty()) {
            return traits;
        }
        if (identifiers(question.question()).stream()
                .noneMatch(id -> answerChunks.stream().anyMatch(chunk -> chunk.text().contains(id)))) {
            traits.add(Trait.PARAPHRASED);
        }
        for (int i = 0; i < answerChunks.size(); i++) {
            Chunk chunk = answerChunks.get(i);
            Optional<String> block = blockContaining(chunk.text(), question.expectedText().get(i));
            if (chunk.index() > 0 || block.map(b -> CALLOUT.matcher(b).find()).orElse(false)) {
                traits.add(Trait.DEEP_DETAIL);
            }
            if (block.map(DerivedTraits::isTableOrCode).orElse(false)) {
                traits.add(Trait.TABLE_OR_CODE);
            }
        }
        return traits;
    }

    static Set<String> identifiers(String question) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(question);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** The paragraph, table or code block of {@code text} that contains the phrase (whitespace-insensitive). */
    static Optional<String> blockContaining(String text, String phrase) {
        String wanted = EvalScorer.normalize(phrase);
        return blocks(text).stream().filter(block -> EvalScorer.normalize(block).contains(wanted)).findFirst();
    }

    /** Splits at blank lines, like the Chunker; a ``` fence is one block even if it contains blank lines. */
    static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            boolean fenceLine = line.strip().startsWith("```");
            if (fenceLine && !inFence) {
                add(blocks, current);
                inFence = true;
                current.append(line).append('\n');
            } else if (fenceLine) {
                current.append(line).append('\n');
                add(blocks, current);
                inFence = false;
            } else if (!inFence && line.isBlank()) {
                add(blocks, current);
            } else {
                current.append(line).append('\n');
            }
        }
        add(blocks, current);
        return blocks;
    }

    private static void add(List<String> blocks, StringBuilder current) {
        if (!current.isEmpty()) {
            blocks.add(current.toString().strip());
            current.setLength(0);
        }
    }

    private static boolean isTableOrCode(String block) {
        return block.startsWith("```") || block.startsWith("|");
    }
}
