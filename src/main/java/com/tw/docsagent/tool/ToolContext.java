package com.tw.docsagent.tool;

/** State shared by all tool calls of one agent run. */
public record ToolContext(SourceCatalog sources) {

    public static ToolContext newRun() {
        return new ToolContext(new SourceCatalog());
    }
}
