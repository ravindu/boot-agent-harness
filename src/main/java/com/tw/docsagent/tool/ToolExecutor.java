package com.tw.docsagent.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs one tool call and never throws: every failure becomes a {@code tool_result} with
 * {@code is_error: true}. A failing tool must not crash the run, and Claude has to receive a
 * result for every {@code tool_use} it sent, otherwise the next API request is rejected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    /** Result plus what the trace needs. */
    public record Execution(ToolResult result, long latencyMs) {
    }

    private final ToolRegistry registry;

    public Execution execute(String toolName, Map<String, Object> input, ToolContext context) {
        Instant start = Instant.now();
        ToolResult result = registry.find(toolName)
                .map(tool -> runSafely(tool, input, context))
                .orElseGet(() -> ToolResult.error("Unknown tool '" + toolName + "'."));
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        log.info("tool call tool={} error={} latencyMs={}", toolName, result.error(), latencyMs);
        return new Execution(result, latencyMs);
    }

    private ToolResult runSafely(AgentTool tool, Map<String, Object> input, ToolContext context) {
        try {
            return tool.execute(input, context);
        } catch (RuntimeException e) {
            // The message goes to Claude, so keep it generic; the stack trace stays in our logs
            log.warn("tool {} failed", tool.name(), e);
            return ToolResult.error("Tool '" + tool.name() + "' failed: " + e.getClass().getSimpleName()
                    + ". Try again with different input or answer without it.");
        }
    }
}
