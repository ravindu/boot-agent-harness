package com.tw.docsagent.retrieval.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a section into chunks of at most {@code maxWords} words, each prefixed with its breadcrumb.
 *
 * <p>Why small chunks: the local all-MiniLM-L6-v2 model embeds only the first ~256 tokens of its
 * input and silently ignores the rest, so a long chunk would be represented by its opening lines.
 *
 * <p>Why the breadcrumb: a chunk like "Add the annotation to the field" means nothing on its own.
 * "Testing > Testcontainers > Service Connections" in front of it gives both the embedding and
 * Claude the context the section heading provided on the page.
 *
 * <p>Splits at blank lines and keeps a code fence together when it fits. Fences larger than one
 * chunk are split by lines (structure-aware chunking that never cuts code is M4).
 */
public class Chunker {

    private final int maxWords;

    public Chunker(int maxWords) {
        this.maxWords = maxWords;
    }

    public List<String> chunk(DocSection section) {
        if (section.body().isBlank()) {
            return List.of();
        }
        String header = section.breadcrumbText() + "\n\n";
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWords = 0;

        for (String block : splitBlocks(section.body())) {
            for (String piece : fitToLimit(block)) {
                int words = countWords(piece);
                if (currentWords > 0 && currentWords + words > maxWords) {
                    chunks.add(header + current.toString().strip());
                    current = new StringBuilder();
                    currentWords = 0;
                }
                current.append(piece).append("\n\n");
                currentWords += words;
            }
        }
        if (currentWords > 0) {
            chunks.add(header + current.toString().strip());
        }
        return chunks;
    }

    /** Paragraphs separated by blank lines; a ``` fence is one block even if it contains blank lines. */
    private static List<String> splitBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        boolean inFence = false;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("```")) {
                inFence = !inFence;
            }
            if (line.isBlank() && !inFence) {
                if (!block.isEmpty()) {
                    blocks.add(block.toString().strip());
                    block = new StringBuilder();
                }
                continue;
            }
            block.append(line).append('\n');
        }
        if (!block.isEmpty()) {
            blocks.add(block.toString().strip());
        }
        return blocks;
    }

    /** A block over the limit is split by lines (or, for one very long line, by words). */
    private List<String> fitToLimit(String block) {
        if (countWords(block) <= maxWords) {
            return List.of(block);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int words = 0;
        for (String line : block.split("\n")) {
            for (String part : splitLongLine(line)) {
                int partWords = countWords(part);
                if (words > 0 && words + partWords > maxWords) {
                    pieces.add(piece.toString().stripTrailing());
                    piece = new StringBuilder();
                    words = 0;
                }
                piece.append(part).append('\n');
                words += partWords;
            }
        }
        if (words > 0) {
            pieces.add(piece.toString().stripTrailing());
        }
        return pieces;
    }

    private List<String> splitLongLine(String line) {
        String[] words = line.trim().split("\\s+");
        if (words.length <= maxWords) {
            return List.of(line);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < words.length; i += maxWords) {
            parts.add(String.join(" ", List.of(words).subList(i, Math.min(words.length, i + maxWords))));
        }
        return parts;
    }

    static int countWords(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }
}
