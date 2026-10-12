package com.tw.docsagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceCatalogTest {

    @Test
    void labelsChunksInOrderAndReusesLabelForTheSameChunk() {
        SourceCatalog catalog = new SourceCatalog();

        assertThat(catalog.register("chunk-a", "A", "https://a").label()).isEqualTo("S1");
        assertThat(catalog.register("chunk-b", "B", "https://b").label()).isEqualTo("S2");
        assertThat(catalog.register("chunk-a", "A", "https://a").label()).isEqualTo("S1");

        assertThat(catalog.all()).hasSize(2);
        assertThat(catalog.resolve("S2")).hasValueSatisfying(s -> assertThat(s.url()).isEqualTo("https://b"));
        assertThat(catalog.resolve("S9")).isEmpty();
    }
}
