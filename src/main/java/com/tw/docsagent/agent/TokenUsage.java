package com.tw.docsagent.agent;

import com.anthropic.models.messages.Usage;

/**
 * Token counts as the API reports them. {@code input} excludes cached tokens: a request's prompt
 * is {@code input + cacheWrite + cacheRead}.
 */
public record TokenUsage(long input, long output, long cacheWrite, long cacheRead) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    public static TokenUsage of(Usage usage) {
        return new TokenUsage(usage.inputTokens(), usage.outputTokens(),
                usage.cacheCreationInputTokens().orElse(0L), usage.cacheReadInputTokens().orElse(0L));
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(input + other.input, output + other.output,
                cacheWrite + other.cacheWrite, cacheRead + other.cacheRead);
    }

    /** Everything processed; what the token budget is checked against. */
    public long total() {
        return input + output + cacheWrite + cacheRead;
    }
}
