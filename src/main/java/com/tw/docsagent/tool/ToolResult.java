package com.tw.docsagent.tool;

/**
 * Output of one tool call, sent back to Claude as a {@code tool_result} block.
 *
 * @param content text Claude reads
 * @param error   becomes {@code is_error: true}: Claude learns the call failed and can change its plan
 */
public record ToolResult(String content, boolean error) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}
