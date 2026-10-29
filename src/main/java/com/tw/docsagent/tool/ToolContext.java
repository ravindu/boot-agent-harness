package com.tw.docsagent.tool;

import com.tw.docsagent.tool.SourceCatalog.Source;
import java.util.List;

/** State shared by all tool calls of one agent run. */
public record ToolContext(SourceCatalog sources) {

    public static ToolContext newRun() {
        return new ToolContext(new SourceCatalog());
    }

    /** The context of a paused run, rebuilt from what was stored when it paused. */
    public static ToolContext restore(List<Source> sources) {
        return new ToolContext(SourceCatalog.restore(sources));
    }
}
