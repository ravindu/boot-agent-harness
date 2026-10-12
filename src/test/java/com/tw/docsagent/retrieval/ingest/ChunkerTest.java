package com.tw.docsagent.retrieval.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    private static final List<String> BREADCRUMB = List.of("Testing", "Testcontainers");

    private static DocSection section(String body) {
        return new DocSection("testing/testcontainers", "testing.testcontainers", "Testcontainers", BREADCRUMB, body);
    }

    private static String words(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> prefix + i).collect(Collectors.joining(" "));
    }

    @Test
    void smallSectionIsOneChunkPrefixedWithItsBreadcrumb() {
        List<String> chunks = new Chunker(50).chunk(section("Short paragraph."));

        assertThat(chunks).containsExactly("Testing > Testcontainers\n\nShort paragraph.");
    }

    @Test
    void splitsAtParagraphsAndRespectsTheWordLimit() {
        String body = words("a", 30) + "\n\n" + words("b", 30) + "\n\n" + words("c", 30);

        List<String> chunks = new Chunker(50).chunk(section(body));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).startsWith("Testing > Testcontainers\n\n");
            assertThat(Chunker.countWords(chunk.substring(chunk.indexOf("\n\n")))).isLessThanOrEqualTo(50);
        });
    }

    @Test
    void keepsACodeFenceWithBlankLinesTogether() {
        String code = "```java\nclass A {\n\n    void a() {}\n\n}\n```";
        List<String> chunks = new Chunker(50).chunk(section(words("w", 45) + "\n\n" + code));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1)).endsWith(code);
    }

    @Test
    void splitsOversizedBlocksByLinesAndWords() {
        List<String> chunks = new Chunker(50).chunk(section(words("x", 120)));

        assertThat(chunks).hasSize(3);
        assertThat(String.join(" ", chunks)).contains("x0", "x119");
    }

    @Test
    void emptySectionProducesNoChunks() {
        assertThat(new Chunker(50).chunk(section("  "))).isEmpty();
    }
}
