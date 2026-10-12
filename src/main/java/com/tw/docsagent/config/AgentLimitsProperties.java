package com.tw.docsagent.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Hard limits for one agent run. A run that hits any of them ends with an explicit status.
 * Defined in M0, enforced by the agent loop from M1.
 */
@Validated
@ConfigurationProperties("docsagent.agent")
public record AgentLimitsProperties(
        @Positive int maxTurns,
        @Positive long maxTotalTokens,
        @NotNull @Positive BigDecimal maxCostUsd,
        @NotNull Duration runTimeout) {
}
