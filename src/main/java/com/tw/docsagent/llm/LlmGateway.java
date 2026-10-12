package com.tw.docsagent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The single place where the application calls the Claude Messages API.
 * Tracing, cost accounting, budgets and retries attach here (M1+), so the agent loop never
 * talks to the SDK directly. Logs ids and token counts only, never prompt content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGateway {

    private final AnthropicClient client;

    public Message send(MessageCreateParams params) {
        Instant start = Instant.now();
        Message message = client.messages().create(params);
        long latencyMs = Duration.between(start, Instant.now()).toMillis();
        log.info("llm call model={} messageId={} stopReason={} inputTokens={} outputTokens={} latencyMs={}",
                message.model().asString(),
                message.id(),
                message.stopReason().map(Object::toString).orElse("none"),
                message.usage().inputTokens(),
                message.usage().outputTokens(),
                latencyMs);
        return message;
    }
}
