package com.tw.docsagent.tool;

import java.util.List;
import java.util.Map;

/**
 * A tool the agent can call. Implementations are Spring beans; {@link ToolRegistry} turns them into
 * Claude tool definitions and {@link ToolExecutor} runs them.
 *
 * <p>Claude never runs a tool itself. It answers with a {@code tool_use} block naming the tool and its
 * arguments; the harness runs {@link #execute} and sends the output back as a {@code tool_result}.
 */
public interface AgentTool {

    /** Name Claude uses in {@code tool_use}. Changing it changes the prompt (and breaks the prompt cache). */
    String name();

    /** Tells Claude what the tool does and, more importantly, when to call it. */
    String description();

    /**
     * JSON Schema of each input property, e.g. {@code "query" -> {type: string, description: ...}}.
     * Tools are declared {@code strict}, so numeric/string bounds are not allowed here; enforce them in {@link #execute}.
     */
    Map<String, Object> inputProperties();

    /** Properties Claude must always send. */
    List<String> requiredInputs();

    /**
     * @param input   arguments from the {@code tool_use} block, already valid against the schema (strict mode)
     * @param context run-scoped state, e.g. the citation catalog
     */
    ToolResult execute(Map<String, Object> input, ToolContext context);
}
