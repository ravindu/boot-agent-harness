package com.tw.docsagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * End-to-end check of the embedding + vector store choice: local ONNX embeddings, stored in
 * pgvector, nearest-neighbour search returns the semantically matching chunk. No API key needed.
 * The first run downloads the ONNX model (~90 MB) into the temp directory.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VectorStoreIT {

    @Autowired
    VectorStore vectorStore;

    private final Document jpaSlice = new Document(
            "@DataJpaTest configures only the JPA components: repositories, an EntityManager and a "
                    + "DataSource. Each test runs in a transaction that is rolled back.",
            Map.of("section", "testing"));
    private final Document actuator = new Document(
            "Actuator exposes production-ready endpoints such as health, metrics and info over HTTP or JMX.",
            Map.of("section", "actuator"));

    @AfterEach
    void cleanUp() {
        vectorStore.delete(List.of(jpaSlice.getId(), actuator.getId()));
    }

    @Test
    void similaritySearchReturnsTheMatchingChunkFirst() {
        vectorStore.add(List.of(jpaSlice, actuator));

        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query("How do I test my Spring Data repositories without starting the whole app?")
                .topK(2)
                .build());

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().getId()).isEqualTo(jpaSlice.getId());
        assertThat(results.getFirst().getMetadata()).containsEntry("section", "testing");
    }
}
