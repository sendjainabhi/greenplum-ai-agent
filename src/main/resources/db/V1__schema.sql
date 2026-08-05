-- =============================================================================
-- Greenplum AI Agent — application database schema
-- Replaces all flat-file storage on CF block storage
-- =============================================================================

-- Admin-level configuration (global prompt, etc.)
CREATE TABLE IF NOT EXISTS app_config (
    key        VARCHAR(200) PRIMARY KEY,
    value      TEXT         NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Email-based access control (allowed-users.txt)
CREATE TABLE IF NOT EXISTS allowed_users (
    email      VARCHAR(255) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- All users who have ever logged in (known-users.txt)
CREATE TABLE IF NOT EXISTS known_users (
    email      VARCHAR(255) PRIMARY KEY,
    first_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Custom role definitions (roles-list.txt; ADMIN is always implicit)
CREATE TABLE IF NOT EXISTS roles (
    name       VARCHAR(100) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Role assigned to each user (user-role-map.txt)
CREATE TABLE IF NOT EXISTS user_roles (
    email      VARCHAR(255) PRIMARY KEY,
    role       VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Per-user AI model and MCP config (users/{id}/config.json)
CREATE TABLE IF NOT EXISTS user_config (
    user_id    VARCHAR(255) PRIMARY KEY,
    config_json TEXT        NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Per-user personal AI preferences (users/{id}/user-prefs.txt)
CREATE TABLE IF NOT EXISTS user_preferences (
    user_id    VARCHAR(255) PRIMARY KEY,
    prefs      TEXT         NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Per-user session state — full JSON blob (users/{id}/sessions.json)
CREATE TABLE IF NOT EXISTS user_sessions (
    user_id    VARCHAR(255) PRIMARY KEY,
    data       TEXT         NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Per-user saved favourite prompts (users/{id}/favourites.json)
CREATE TABLE IF NOT EXISTS user_favourites (
    user_id    VARCHAR(255) PRIMARY KEY,
    data       TEXT         NOT NULL DEFAULT '[]',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- LangChain4j conversation memory per session (users/{id}/memory/{session}.json)
CREATE TABLE IF NOT EXISTS user_memory (
    user_id    VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    messages   TEXT         NOT NULL DEFAULT '[]',
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (user_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_user_memory_user ON user_memory (user_id);
