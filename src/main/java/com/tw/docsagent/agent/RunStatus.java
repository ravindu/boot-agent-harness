package com.tw.docsagent.agent;

/** How a run ended. Every run ends in exactly one of these; none ends silently. */
public enum RunStatus {
    /** Still in the loop (only seen while a run is being traced). */
    RUNNING,
    /** Claude finished with {@code end_turn}. */
    COMPLETED,
    /** Hit {@code docsagent.agent.max-turns} model calls. */
    MAX_TURNS_REACHED,
    /** Hit the token or cost budget. */
    BUDGET_EXCEEDED,
    /** Hit {@code docsagent.agent.run-timeout} (checked between steps). */
    TIMED_OUT,
    /** Claude's answer was cut off by {@code max_tokens}. */
    TRUNCATED,
    /** Claude called askUser; the run waits for a human answer (HTTP 202). Resumes with the answer. */
    WAITING_FOR_USER,
    /** Claude declined ({@code stop_reason=refusal}); the category is in the status detail. */
    REFUSED,
    /** API error after the SDK's retries, or an unexpected stop reason. */
    FAILED
}
