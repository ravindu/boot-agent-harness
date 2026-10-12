package com.tw.docsagent.agent;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Builds the Messages API responses a fake model "sends" in tests. */
public final class ScriptedResponses {

    public static final String MODEL = "claude-sonnet-5-5";
    private static final AtomicInteger IDS = new AtomicInteger();

    private ScriptedResponses() {
    }

    /** @param blocks {@link TextBlock}, {@link ToolUseBlock} or {@link ThinkingBlock}, in order */
    public static Message response(StopReason stopReason, long inputTokens, Object... blocks) {
        Message.Builder builder = Message.builder()
                .id("msg_" + IDS.incrementAndGet())
                .model(MODEL)
                .stopReason(stopReason)
                .stopSequence(Optional.empty())
                .container(Optional.empty())
                .stopDetails(Optional.empty())
                .diagnostics(Optional.empty())
                .content(List.of())
                .usage(Usage.builder().inputTokens(inputTokens).outputTokens(100)
                        .cacheCreationInputTokens(0).cacheReadInputTokens(0)
                        .cacheCreation(Optional.empty()).inferenceGeo(Optional.empty())
                        .serverToolUse(Optional.empty()).serviceTier(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .build());
        for (Object block : blocks) {
            switch (block) {
                case TextBlock text -> builder.addContent(text);
                case ToolUseBlock toolUse -> builder.addContent(toolUse);
                case ThinkingBlock thinking -> builder.addContent(thinking);
                default -> throw new IllegalArgumentException(block.toString());
            }
        }
        return builder.build();
    }

    public static Message toolUseResponse(ToolUseBlock... calls) {
        return response(StopReason.TOOL_USE, 1000, (Object[]) calls);
    }

    public static TextBlock text(String text) {
        return TextBlock.builder().text(text).citations(Optional.empty()).build();
    }

    public static ToolUseBlock toolUse(String name, Map<String, Object> input) {
        return toolUse("toolu_" + IDS.incrementAndGet(), name, input);
    }

    public static ToolUseBlock toolUse(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(JsonValue.from(input))
                .caller(DirectCaller.builder().build()).build();
    }
}
