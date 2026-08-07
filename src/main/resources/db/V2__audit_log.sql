-- User audit log — email, action, timestamp only. No query content stored.
CREATE TABLE IF NOT EXISTS user_audit_log (
    id     BIGSERIAL    PRIMARY KEY,
    email  VARCHAR(255) NOT NULL,
    action VARCHAR(50)  NOT NULL,
    ts     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_ts    ON user_audit_log (ts);
CREATE INDEX IF NOT EXISTS idx_audit_email ON user_audit_log (email);
