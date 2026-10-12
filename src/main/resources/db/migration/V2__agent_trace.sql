-- One row per agent run. Totals are updated when the run finishes; the steps have the details.
CREATE TABLE agent_run
(
    id                 uuid PRIMARY KEY,
    question           text           NOT NULL,
    model              text           NOT NULL,
    status             text           NOT NULL,
    status_detail      text,
    answer             text,
    citations          jsonb,
    invented_citations jsonb,
    turns              int            NOT NULL DEFAULT 0,
    input_tokens       bigint         NOT NULL DEFAULT 0,
    output_tokens      bigint         NOT NULL DEFAULT 0,
    cache_write_tokens bigint         NOT NULL DEFAULT 0,
    cache_read_tokens  bigint         NOT NULL DEFAULT 0,
    cost_usd           numeric(12, 6) NOT NULL DEFAULT 0,
    -- Optimistic locking for M3 pause/resume: UPDATE ... WHERE version = ?
    version            int            NOT NULL DEFAULT 0,
    started_at         timestamptz    NOT NULL,
    finished_at        timestamptz
);

CREATE INDEX agent_run_started_at_idx ON agent_run (started_at DESC);

-- The trace: one row per model call or tool call, in execution order.
CREATE TABLE agent_step
(
    run_id             uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    step_no            int         NOT NULL,
    turn               int         NOT NULL,
    type               text        NOT NULL CHECK (type IN ('MODEL_CALL', 'TOOL_CALL')),
    -- MODEL_CALL
    model              text,
    message_id         text,
    stop_reason        text,
    input_tokens       bigint,
    output_tokens      bigint,
    cache_write_tokens bigint,
    cache_read_tokens  bigint,
    cost_usd           numeric(12, 6),
    detail             text,
    -- TOOL_CALL
    tool_use_id        text,
    tool_name          text,
    tool_input         jsonb,
    tool_error         boolean,
    tool_output_chars  int,
    latency_ms         bigint      NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, step_no)
);

-- The exact message history sent to Claude (SDK JSON). Needed to resume a paused run (M3).
CREATE TABLE agent_message
(
    run_id     uuid        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    seq        int         NOT NULL,
    role       text        NOT NULL,
    content    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);
