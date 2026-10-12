package com.tw.docsagent.trace;

/**
 * One tool execution.
 *
 * @param inputJson   arguments exactly as Claude sent them
 * @param outputChars size of the result sent back (the result itself is in the message history)
 */
public record ToolCallStep(
        int stepNo,
        int turn,
        String toolUseId,
        String toolName,
        String inputJson,
        boolean error,
        int outputChars,
        long latencyMs) {
}
