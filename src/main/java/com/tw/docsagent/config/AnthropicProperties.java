package com.tw.docsagent.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Anthropic client settings. The API key is deliberately not here: the SDK reads
 * {@code ANTHROPIC_API_KEY} from the environment, so it never passes through Spring config.
 *
 * @param agentModel  model that runs the agent loop
 * @param agentEffort how much the agent model thinks: low, medium, high, xhigh or max (M2 measures which pays off)
 * @param agentMaxTokens output cap per agent model call (thinking + text + tool calls)
 * @param helperModel cheap model for helper steps (query rewriting, chunk context, judging)
 * @param timeout     per-request HTTP timeout
 * @param maxRetries  SDK retries for 408/409/429/5xx and connection errors
 */
@Validated
@ConfigurationProperties("docsagent.anthropic")
public record AnthropicProperties(
        @NotBlank String agentModel,
        @NotBlank String agentEffort,
        @Positive long agentMaxTokens,
        @NotBlank String helperModel,
        @NotNull Duration timeout,
        @PositiveOrZero int maxRetries) {
}
