package com.tw.docsagent.trace;

import com.tw.docsagent.agent.TokenUsage;
import java.math.BigDecimal;

/** One call to the Messages API. */
public record ModelCallStep(
        int stepNo,
        int turn,
        String model,
        String messageId,
        String stopReason,
        TokenUsage usage,
        BigDecimal costUsd,
        long latencyMs,
        String detail) {
}
