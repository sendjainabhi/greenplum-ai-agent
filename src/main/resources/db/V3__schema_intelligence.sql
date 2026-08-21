-- Business glossary — maps business terms to exact table/column references.
-- The agent injects matching entries into the prompt based on keywords in the user query.
CREATE TABLE IF NOT EXISTS business_glossary (
    id          SERIAL       PRIMARY KEY,
    term        VARCHAR(100) NOT NULL,
    table_ref   VARCHAR(200),
    column_ref  VARCHAR(200),
    rule        TEXT         NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE (term)
);

-- Query templates — canonical SQL patterns for common business questions.
-- The agent injects matching hints into the prompt to ensure consistent SQL shape.
CREATE TABLE IF NOT EXISTS query_templates (
    id          SERIAL       PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    keywords    TEXT         NOT NULL,
    hint_sql    TEXT         NOT NULL,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
