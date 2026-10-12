package com.tw.docsagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.retrieval.ingest.IngestionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The real thing: ingest the Boot 4.1.1 Testing + Data docs from GitHub, then let Claude answer with
 * searchDocs. Costs a few cents. Run with: {@code ./mvnw verify -Plive -Dit.test=AgentLiveIT}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentLiveIT {

    @Autowired
    IngestionService ingestionService;

    @Autowired
    AgentLoop agentLoop;

    @Test
    void answersWithACitationOfTheServiceConnectionsSection() {
        ingestionService.ingestAll();

        AgentRunResult result = agentLoop.run("How do I use @ServiceConnection with Testcontainers?");

        System.out.printf("live run: status=%s turns=%d tokens=%d costUsd=%s citations=%s invented=%s%n%s%n",
                result.status(), result.turns(), result.usage().total(), result.costUsd(),
                result.citations(), result.inventedCitations(), result.answer());
        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.inventedCitations()).isEmpty();
        assertThat(result.citations()).anySatisfy(source ->
                assertThat(source.url()).endsWith("#testing.testcontainers.service-connections"));
    }
}
