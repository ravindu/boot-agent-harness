package com.tw.docsagent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    static AgentTool tool(String name, ToolResult result) {
        return new AgentTool() {
            public String name() { return name; }
            public String description() { return "Test tool " + name; }
            public Map<String, Object> inputProperties() { return Map.of("q", Map.of("type", "string")); }
            public List<String> requiredInputs() { return List.of("q"); }
            public ToolResult execute(Map<String, Object> input, ToolContext context) { return result; }
        };
    }

    @Test
    void definitionsAreStrictAndSortedByName() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(tool("zeta", null), tool("alpha", null)));

        List<Tool> definitions = registry.definitions();

        assertThat(definitions).extracting(Tool::name).containsExactly("alpha", "zeta");
        String json = ObjectMappers.jsonMapper().writeValueAsString(definitions.getFirst());
        assertThat(json)
                .contains("\"strict\":true")
                .contains("\"additionalProperties\":false")
                .contains("\"properties\":{\"q\":{\"type\":\"string\"}}")
                .contains("\"required\":[\"q\"]");
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("a", null), tool("a", null))))
                .isInstanceOf(IllegalStateException.class);
    }
}
