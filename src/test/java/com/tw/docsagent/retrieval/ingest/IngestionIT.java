package com.tw.docsagent.retrieval.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.retrieval.source.DocsSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Full pipeline on fixture docs: parse, chunk, embed locally, store in pgvector, search. */
@SpringBootTest(properties = "docsagent.docs.pages=testing/testcontainers")
@Import({TestcontainersConfiguration.class, IngestionIT.FixtureSource.class})
class IngestionIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureSource {
        @Bean
        @Primary
        DocsSource fixtureDocsSource() {
            return new FixtureDocsSource();
        }
    }

    @Autowired
    IngestionService ingestionService;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    JdbcClient jdbc;

    @Test
    void ingestsFixturePageAndFindsTheRightSection() {
        IngestionResult result = ingestionService.ingestAll();

        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.sections()).isEqualTo(3);
        assertThat(result.resolvedExamples()).isEqualTo(1);
        assertThat(result.missingExamples()).hasSize(1);
        assertThat(countChunks()).isEqualTo(result.chunks());

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                // Wiring check, not a quality check: retrieval quality is measured by the M2 eval suite
                .query("What is a service connection to a remote service?")
                .topK(1)
                .build());

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().getMetadata())
                .containsEntry("anchor", "testing.testcontainers.service-connections")
                .containsEntry("bootVersion", "4.1.1")
                .containsEntry("url", "https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html"
                        + "#testing.testcontainers.service-connections");
    }

    @Test
    void reIngestingReplacesChunksInsteadOfDuplicatingThem() {
        int first = ingestionService.ingestAll().chunks();
        int second = ingestionService.ingestAll().chunks();

        assertThat(second).isEqualTo(first);
        assertThat(countChunks()).isEqualTo(first);
    }

    private int countChunks() {
        return jdbc.sql("select count(*) from vector_store where metadata->>'page' = 'testing/testcontainers'")
                .query(Integer.class).single();
    }
}
