package com.tw.docsagent.api;

import static com.tw.docsagent.agent.ScriptedResponses.response;
import static com.tw.docsagent.agent.ScriptedResponses.text;
import static com.tw.docsagent.agent.ScriptedResponses.toolUse;
import static com.tw.docsagent.agent.ScriptedResponses.toolUseResponse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anthropic.models.messages.StopReason;
import com.jayway.jsonpath.JsonPath;
import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.llm.LlmGateway;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Whole stack except the model: HTTP → agent loop → real searchDocs over pgvector → trace in
 * Postgres → HTTP. Claude is replaced by scripted responses, so no API key and no cost.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentRunIT {

    @MockitoBean
    LlmGateway llm;

    @Autowired
    WebApplicationContext context;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    JdbcClient jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        vectorStore.add(List.of(new Document("7f0c1a6e-2b7a-4e55-9a51-0a6f2c1d9e01",
                "Testing > Testcontainers > Service Connections\n\nAnnotate the container with @ServiceConnection.",
                Map.of("bootVersion", "4.1.1", "page", "testing/testcontainers",
                        "anchor", "testing.testcontainers.service-connections",
                        "breadcrumb", "Testing > Testcontainers > Service Connections",
                        "url", "https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html"
                                + "#testing.testcontainers.service-connections"))));
    }

    @Test
    void answersAQuestionAndStoresTheTrace() throws Exception {
        when(llm.send(any())).thenReturn(
                toolUseResponse(toolUse("searchDocs", Map.of("query", "@ServiceConnection", "topK", 3))),
                response(StopReason.END_TURN, 2000, text("Use @ServiceConnection on the container field [S1].")));

        String body = mvc.perform(post("/api/agent/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"How do I use @ServiceConnection?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.answer").value("Use @ServiceConnection on the container field [S1]."))
                .andExpect(jsonPath("$.citations[0].label").value("S1"))
                .andExpect(jsonPath("$.citations[0].url").value(
                        "https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html"
                                + "#testing.testcontainers.service-connections"))
                .andExpect(jsonPath("$.inventedCitations").isEmpty())
                .andExpect(jsonPath("$.turns").value(2))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");

        mvc.perform(get("/api/agent/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.inputTokens").value(3000))
                .andExpect(jsonPath("$.costUsd").value(0.008))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].type").value("MODEL_CALL"))
                .andExpect(jsonPath("$.steps[0].stopReason").value("tool_use"))
                .andExpect(jsonPath("$.steps[1].type").value("TOOL_CALL"))
                .andExpect(jsonPath("$.steps[1].toolName").value("searchDocs"))
                .andExpect(jsonPath("$.steps[1].toolInput.query").value("@ServiceConnection"))
                .andExpect(jsonPath("$.steps[1].toolError").value(false))
                .andExpect(jsonPath("$.steps[2].stopReason").value("end_turn"));

        // Full history kept for M3 resume: question, tool_use turn, tool_result, final answer
        List<String> roles = jdbc.sql("select role from agent_message where run_id = cast(:id as uuid) order by seq")
                .param("id", runId).query(String.class).list();
        org.assertj.core.api.Assertions.assertThat(roles).containsExactly("user", "assistant", "user", "assistant");
        String toolResult = jdbc.sql("select content::text from agent_message where run_id = cast(:id as uuid) and seq = 3")
                .param("id", runId).query(String.class).single();
        org.assertj.core.api.Assertions.assertThat(toolResult)
                .contains("tool_result", "<doc id=\\\"S1\\\"", "Annotate the container with @ServiceConnection.");
    }

    @Test
    void blankQuestionIsAProblemDetail() throws Exception {
        mvc.perform(post("/api/agent/runs").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void unknownRunIsANotFoundProblemDetail() throws Exception {
        mvc.perform(get("/api/agent/runs/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No agent run 00000000-0000-0000-0000-000000000000"));
    }
}
