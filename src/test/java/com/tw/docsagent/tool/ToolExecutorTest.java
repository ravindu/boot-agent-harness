package com.tw.docsagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {

    private final AgentTool failing = new AgentTool() {
        public String name() { return "failing"; }
        public String description() { return "always fails"; }
        public Map<String, Object> inputProperties() { return Map.of(); }
        public List<String> requiredInputs() { return List.of(); }
        public ToolResult execute(Map<String, Object> input, ToolContext context) {
            throw new IllegalStateException("database password is hunter2");
        }
    };

    private final ToolExecutor executor = new ToolExecutor(new ToolRegistry(List.of(
            ToolRegistryTest.tool("echo", ToolResult.ok("hello")), failing)));

    @Test
    void returnsTheToolResult() {
        assertThat(executor.execute("echo", Map.of(), ToolContext.newRun()).result())
                .isEqualTo(ToolResult.ok("hello"));
    }

    @Test
    void unknownToolBecomesAnErrorResult() {
        ToolResult result = executor.execute("deleteEverything", Map.of(), ToolContext.newRun()).result();

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("Unknown tool 'deleteEverything'");
    }

    @Test
    void exceptionBecomesAnErrorResultWithoutLeakingTheMessage() {
        ToolResult result = executor.execute("failing", Map.of(), ToolContext.newRun()).result();

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("IllegalStateException").doesNotContain("hunter2");
    }
}
