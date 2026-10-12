package com.tw.docsagent.tool;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * All {@link AgentTool} beans, and their definitions in the shape the Messages API expects.
 *
 * <p>Definitions are sorted by name: tools are the first thing in the prompt, so a stable order keeps
 * the request prefix byte-identical between calls (prompt caching depends on that, M3).
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        this.tools = tools.stream()
                .sorted(Comparator.comparing(AgentTool::name))
                .collect(Collectors.toMap(AgentTool::name, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException("Duplicate tool name " + a.name());
                        },
                        LinkedHashMap::new));
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * {@code strict: true} makes the API guarantee that {@code tool_use.input} matches the schema,
     * which requires {@code additionalProperties: false} on every object.
     */
    public List<Tool> definitions() {
        return tools.values().stream()
                .map(tool -> Tool.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .strict(true)
                        .inputSchema(Tool.InputSchema.builder()
                                .properties(JsonValue.from(tool.inputProperties()))
                                .required(tool.requiredInputs())
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                        .build())
                .toList();
    }
}
