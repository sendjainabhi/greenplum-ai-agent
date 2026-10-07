-- Replace per-call rows with a daily aggregate table.
-- One row per (day, model_name) — counters are incremented on each LLM call via UPSERT.
DROP TABLE IF EXISTS model_usage;

CREATE TABLE IF NOT EXISTS model_usage (
    day           DATE          NOT NULL,
    model_name    VARCHAR(100)  NOT NULL,
    call_count    INTEGER       NOT NULL DEFAULT 0,
    input_tokens  BIGINT        NOT NULL DEFAULT 0,
    output_tokens BIGINT        NOT NULL DEFAULT 0,
    total_tokens  BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (day, model_name)
);
