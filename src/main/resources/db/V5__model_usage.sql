CREATE TABLE IF NOT EXISTS model_usage (
    id            SERIAL PRIMARY KEY,
    ts            TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    model_name    VARCHAR(100),
    input_tokens  INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_model_usage_ts ON model_usage (ts);
