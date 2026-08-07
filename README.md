# Greenplum AI Analytics Agent

> [!WARNING]
> **PROOF OF CONCEPT — Not for production use.**

A Cloud Foundry-native AI assistant for the Tanzu data platform. Authenticate via Broadcom AuthHub SSO, bind your Tanzu GenAI model, Greenplum MCP, and Tanzu Postgres services — users sign in with their Broadcom credentials and start chatting with their Greenplum database in natural language. All persistent data is stored in a dedicated PostgreSQL database; no manual file management required.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Features](#features)
3. [Example Prompts](#example-prompts)
4. [MCP Capabilities](#mcp-capabilities)
5. [Admin Panel & User Preferences](#admin-panel)
6. [Role-Based Access Control](#role-based-access-control)
7. [Platform Services](#platform-services)
8. [PostgreSQL Database](#postgresql-database)
9. [Data Retention](#data-retention)
10. [Deployment](#deployment)
11. [Architecture](#architecture)
12. [Broadcom SSO Integration — Technical Deep Dive](#broadcom-sso-integration--technical-deep-dive)
13. [Troubleshooting](#troubleshooting)

---

## How It Works

1. User opens the app URL → redirected to Broadcom AuthHub SSO (Okta) for authentication
2. On successful login the JWT `sub` claim becomes the user ID for per-user session isolation
3. At startup the app reads all AI model, MCP, and storage credentials from `VCAP_SERVICES` — nothing to configure manually
4. Admins can restrict access to specific Broadcom email addresses and assign each user a role
5. Every chat request is tagged with the user's role — the AI enforces data access boundaries defined in the global prompt
6. Each user's sessions, AI memory, and saved prompts are stored independently in the PostgreSQL database

**Header status indicators:**

| Indicator | Meaning |
| :--- | :--- |
| **Model name + dot** | Shows the bound model name (e.g. `gpt-4o`); dot is green when reachable. Hover for full model name. |
| **Greenplum dot** | Green when Greenplum MCP server HTTP is reachable |
| **OpenMetadata dot** | Shown only when that service is bound; green/red per connection |

---

## Features

### 🔐 Authentication — Broadcom SSO

- **Single sign-on** via Broadcom corporate credentials — no passwords stored in the app
- **Per-user isolation** — sessions and saved data are private to each authenticated user
- **Access control** — admin can restrict access to specific Broadcom email addresses
- **Sign out** — ends your session; next visit requires re-authentication

### 💬 Chat & Sessions

- **Up to 10 concurrent sessions** — independent tabs with separate AI memory and titles
- **Full session persistence** — saved and restored on next login from any browser
- **Auto-title** — first message becomes the tab title
- **Session rename / delete** — inline from the sidebar
- **Cancel** — stop the AI response mid-generation
- **Prompt autocomplete** — suggestions from your prompt history as you type

### 📄 PDF Export

- **One-click export** — Export PDF button below every AI response
- **Large document support** — responses of any length exported correctly; rendered page-by-page to stay within browser canvas limits (fixes blank PDF on 30+ page responses)
- **Layout safe** — tables, code blocks, and charts never split across pages
- **Charts included** — visualisations are captured and embedded in the PDF
- **Greenplum branding** — forest-green header with logo
- **Auto filename** — named after the query and today's date

### ⭐ Saved Favourites

Save any prompt with a label for quick reuse. Accessible from the sidebar; persisted across sessions.

### 👤 User Preferences

Each user can set personal AI instructions that apply only to their sessions and take priority over the admin global prompt.

- **Access** — click your **initials circle** in the top-left of the header
- **Edit** — click ✏️ Edit to modify, **Save** to persist immediately
- **Persisted server-side** — stored in the `user_preferences` table in PostgreSQL; survives logouts, browser changes, and app restages
- **Priority** — appended after the admin global prompt, so user instructions override global defaults for that user

**Useful preference examples:**

| Category | Example |
| :--- | :--- |
| Region default | "I cover the AMER region — default to AMER unless I specify otherwise" |
| Product focus | "I work on the Spring Runtime team — focus on Spring product data" |
| Fiscal period | "Default to FY25 data unless I specify a different period" |
| Formatting | "Format large numbers with commas. Show amounts in millions rounded to 2 decimal places." |
| SQL visibility | "Do not show SQL queries in responses" |
| Response style | "Keep answers concise — no more than 3 sentences unless I ask for details" |
| Rankings | "When showing rankings, always include top 5 and bottom 5" |

### 🔐 Admin Panel

- Four-tab interface protected by an admin PIN
- **Global Prompt** — pre-training instructions applied to all users; define role boundaries here
- **Access Control** — add/remove users with a mandatory role assignment per user
- **Roles** — create/delete custom roles; reassign or delete role assignments for any user
- **Audit Log** — visible only to permanent admins; shows login, logout, and query events with date filtering and sortable columns

### 📋 Audit Log

Server-side activity log for permanent admins to monitor app usage.

- **Events logged** — `LOGIN`, `LOGOUT`, `QUERY` (no query content, no IP address, no session IDs)
- **Fields** — email address, timestamp, action type
- **Retention** — 15 days; older entries are automatically purged nightly
- **Access** — only permanent admins (configured via `PERMANENT_ADMIN_EMAILS` in `manifest.yml`) can view the audit tab
- **Filters** — date range filter (from/to) with reset
- **Sorting** — click any column header (Timestamp, Email, Action) to sort ascending/descending
- **Pagination** — 50 rows per page

### 🎭 Role-Based Access Control

- Global admin creates named roles and defines each role's data boundaries in the Global Prompt
- Every chat request is injected with `[USER ROLE: X]` so the AI enforces boundaries automatically
- **ADMIN** is the only built-in role and grants full access — it cannot be deleted
- Users with no assigned role default to ADMIN (backward compatible)
- Permanent admin accounts are protected at the CF environment level — cannot be overridden from the UI

### 🎨 UI

- **Dark / Light mode** — persisted per user
- **Theme-aware** — all modals, inputs, and status indicators adapt to the selected theme
- **Compact header** — initials avatar (click for preferences), icon-only action buttons with tooltips, model name + status dots

---

## Example Prompts

**Greenplum:**

| Prompt | Tool |
| :--- | :--- |
| "Check bloat in the 'sales' table" | `checkTableBloat` |
| "Show cluster status" | `getClusterStatus` |
| "List all tables in the finance schema" | `executeQuery` |
| "Show me the top 10 largest tables by size" | `executeQuery` |
| "Are there any down segments?" | `getClusterStatus` |

**OpenMetadata:**

| Prompt | Tool |
| :--- | :--- |
| "What data services are available in the catalog?" | `listEntities` |
| "Show me all tables in the telco schema" | `searchAssets` |
| "What columns does the sales table have?" | `searchAssets` → `getEntityDetails` |
| "Show lineage for the orders table" | `getLineage` |
| "Are there data quality tests for the customers table?" | `getDataQualityResults` |

---

## MCP Capabilities

### Greenplum

| Tool | Purpose |
| :--- | :--- |
| `executeQuery` | Safe `SELECT` queries — blocks all write/DDL operations |
| `checkTableBloat` | Identifies tables with excessive dead tuples and recommends `VACUUM` |
| `getClusterStatus` | Segment health, mirroring state, and replication status |

Schema introspection (`information_schema.columns`) is performed before querying any table.

### OpenMetadata

| Tool | Purpose |
| :--- | :--- |
| `searchAssets` | Keyword search across tables, dashboards, pipelines, topics, ML models, glossary terms |
| `getEntityDetails` | Full metadata: columns, tags, owners, descriptions, service details |
| `getLineage` | Upstream sources and downstream consumers (3 hops by default) |
| `listEntities` | Overview list by entity type |
| `getDataQualityResults` | Data quality test results for a table |
| `rootCauseAnalysis` | Upstream failures and downstream impact |

---

## Admin Panel

Access: click the **🔐** button in the header (visible only to users with the `ADMIN` role) and enter the `ADMIN_PIN`.

### Prompt Priority Order

Instructions are applied in this order on every chat request — later entries take priority:

1. **System prompt** (`system-prompt.txt`) — embedded in the JAR; defines core AI behaviour and rules
2. **User role tag** — `[USER ROLE: X]` injected automatically based on the user's assigned role
3. **Admin global prompt** (`global-prompt.txt`) — set via the Admin Panel; applies to all users; contains role boundary definitions
4. **User personal preferences** (`users/{userId}/user-prefs.txt`) — set per-user via the initials button; overrides global

### Tab 1 — Global Prompt

A system instruction appended to every chat request for every user. Define role boundaries here alongside general data governance rules.

- Stored in the `app_config` table (`key = 'global_prompt'`) in PostgreSQL; read fresh on every request
- Click **✏️ Edit** to modify, **Save** to apply immediately (no restart needed)
- Leave blank to disable

**Sample structure with role definitions:**

```
==============================
ROLE-BASED ACCESS CONTROL
==============================
Apply the rules below strictly based on [USER ROLE: X].

--- ROLE: ADMIN ---
Full access to all schemas, tables, and data.

--- ROLE: READ_ONLY ---
SELECT queries only. Never generate INSERT, UPDATE, DELETE, DROP, or ALTER.

--- ROLE: AMER_ANALYST ---
Only query data where region = 'AMER'. Refuse requests for other regions.

--- ROLE: <YOUR_CUSTOM_ROLE> ---
[Define data scope and restrictions here]

==============================
[General rules below — apply to all roles]
==============================
```

### Tab 2 — Access Control

Email allowlist stored in the `allowed_users` table in PostgreSQL.

| State | Behaviour |
| :--- | :--- |
| Table empty | **All** authenticated Broadcom users are allowed (fail-open) |
| Table has entries | Only listed email addresses are admitted; others get a 403 |

**UI actions:**
- Enter an email, select a role from the dropdown (required), click **Add** — saves automatically
- Click **👁 Show User List** to view current entries with their assigned roles
- Change a user's role inline via the role dropdown next to their email — saves immediately
- Click **✕** to remove a user — saves automatically

### Tab 3 — Roles

Manage custom role names and assign roles to all users.

- **ADMIN** is the only pre-built role — it cannot be deleted and grants full access
- Add new roles (e.g. `READ_ONLY`, `AMER_ANALYST`, `EMEA_VIEWER`) using the text field
- Role names are uppercase alphanumeric + underscore only
- A role cannot be deleted while it is assigned to any user — reassign first
- **User Role Assignments** panel shows every user from the allowlist with an inline dropdown to change their role
- Users removed from the allowlist appear with a red strikethrough and a `removed` badge — their role assignment entry can be deleted with the **✕** button
- Removing a user from Access Control automatically cascades and removes their role assignment from the database

### Tab 4 — Audit Log (permanent admins only)

Activity log for monitoring who is accessing the application.

| Column | Content |
| :--- | :--- |
| Timestamp | UTC timestamp of the event |
| Email | Broadcom email of the user |
| Action | `LOGIN`, `LOGOUT`, or `QUERY` |

- **Date filter** — filter by from/to date; click **Reset** to clear
- **Sorting** — click any column header to sort; click again to toggle direction (▲/▼)
- **Pagination** — 50 rows per page; navigate with **< Prev** / **Next >**
- **Retention** — rows older than 15 days are purged automatically each night at 02:15 UTC
- Only visible when the authenticated user's email is in `PERMANENT_ADMIN_EMAILS`

---

## Role-Based Access Control

### How It Works

Every chat request is automatically prefixed with `[USER ROLE: X]` before the global prompt is appended. The AI reads both the role tag and the role definitions in the global prompt and applies the matching access rules.

```
User message
  + [USER ROLE: AMER_ANALYST]
  + [GLOBAL POLICY INSTRUCTIONS: ... role definitions ...]
  + [USER PERSONAL PREFERENCES: ...]
         ↓
     AI enforces AMER_ANALYST boundaries
```

### Default Behaviour

| Condition | Effective Role |
| :--- | :--- |
| No role assigned to user | `ADMIN` (full access) |
| `user-role-map.txt` absent | `ADMIN` for all users |
| User is a permanent admin | Always `ADMIN` — cannot be overridden from the UI |

### Permanent Admins

Certain accounts can be designated as permanent admins via the `PERMANENT_ADMIN_EMAILS` env var in `manifest.yml`. Their role is always `ADMIN` regardless of what is stored in `user-role-map.txt`. This protects against accidental lockout.

```yaml
env:
  PERMANENT_ADMIN_EMAILS: first.admin@your-company.com,second.admin@your-company.com
```

> Changing permanent admins requires editing `manifest.yml` and running `mvn clean package && cf push`.

### Storage (PostgreSQL)

| Table | Content |
| :--- | :--- |
| `roles` | Custom role names; `ADMIN` is always implicit and cannot be deleted |
| `user_roles` | `email → role` assignment per user |

### Admin Panel Visibility

The 🔐 Admin button in the header is hidden for non-ADMIN users. It is shown only when `/api/user/current-role` returns `ADMIN` at login time.

---

## Platform Services

The app is driven entirely by CF service bindings. The ops team creates services once per environment.

### SSO — Tanzu AuthHub (`p-identity`)

Provides Broadcom OAuth2/OIDC authentication. The app reads `auth_domain`, `client_id`, and `client_secret` from the binding and auto-configures Spring Security OAuth2.

```bash
# Bind the Tanzu SSO service instance (created by platform ops):
cf bind-service greenplum-ai-agent <sso-service-instance>
```

Credential keys resolved from VCAP (both underscore and hyphen variants are tried):

| Key | Description |
| :--- | :--- |
| `auth_domain` / `auth-domain` | AuthHub UAA base URL (e.g. `https://<sso-instance>.login.<your-cf-domain>`) |
| `client_id` / `client-id` | OAuth2 client ID |
| `client_secret` / `client-secret` | OAuth2 client secret |

> The app sets explicit OAuth2 endpoint URIs (`/oauth/authorize`, `/oauth/token`, `/userinfo`, `/token_keys`) and does **not** use OIDC discovery — setting `issuer-uri` causes a startup-time HTTP call that times out in air-gapped environments.

### AI Model — Tanzu GenAI (`ai-models`)

Bind the Tanzu GenAI marketplace service (label `ai-models`). Credentials are resolved from CredHub by the Java buildpack at container startup.

Supported credential formats:

| Format | Credential path |
| :--- | :--- |
| **Tanzu GenAI v2** | `credentials.endpoint.{openai_api_base, api_key, name}` |
| **Tanzu GenAI v1** | `credentials.{api_url, api_key}` |
| **User-provided** | `credentials.{provider, baseUrl, apiKey, modelName}` |

If `modelName` is absent the app auto-discovers the first non-embedding model from the `/models` endpoint.

Supported providers: **OpenAI-compatible** (vLLM, LMStudio, ChatGPT), **Anthropic**, **Ollama**.

### Tanzu Postgres (`greenplum-agent-db`)

Tanzu Postgres `db-small` service (20 GB). All persistent app data is stored here. The app detects the binding via `VCAP_SERVICES` at startup and activates `PostgresAgentDao` automatically — no manual configuration required.

```bash
# Create Tanzu Postgres instance (ops team, once per environment):
cf create-service postgres db-small greenplum-agent-db

# Bind to the app:
cf bind-service greenplum-ai-agent greenplum-agent-db
cf restage greenplum-ai-agent
```

Schema is managed by Flyway (`V1__schema.sql`) and applied automatically on first startup.

### Block Storage (`greenplum-agent-storage`) — Backup Only

Block storage was the original persistence layer and is retained as a backup. It is **no longer used by the app** — all reads and writes go to PostgreSQL. The volume remains bound so the original files are preserved.

```bash
# The service is still bound but commented out in manifest.yml:
# - greenplum-agent-storage
```

> To roll back to file-based storage: uncomment `greenplum-agent-storage` and `AGENT_DATA_DIR` in `manifest.yml`, remove the `greenplum-agent-db` binding, and `cf push`.

### Greenplum MCP (`gp-mcp-greenplum`)

User-provided service pointing to a running Greenplum MCP CF app.

```bash
ENCODED_AUTH=$(echo -n "<your-db-user>:<your-db-password>" | base64)

cf create-user-provided-service gp-mcp-greenplum \
  -p "{
    \"type\": \"greenplum\",
    \"url\":  \"https://<your-mcp-app-route>/mcp\",
    \"auth\": \"Basic ${ENCODED_AUTH}\"
  }"
```

> The MCP server is a separately deployed CF app. See **[GP-MCP-SERVER.md](GP-MCP-SERVER.md)** for full deployment steps including `config.yaml`, `policy.yaml`, `start.sh`, endpoint testing, and troubleshooting.

> The green dot in the header indicates the MCP HTTP endpoint is reachable — not necessarily that the Greenplum database port (5432) is open. Database queries require the firewall to permit traffic from the MCP app to the database.

### OpenMetadata MCP (`gp-mcp-openmetadata`) — optional

```bash
cf create-user-provided-service gp-mcp-openmetadata \
  -p '{
    "type":  "openmetadata",
    "url":   "https://om-mcp.internal/mcp",
    "auth":  "Bearer <jwt>"
  }'
```

---

## PostgreSQL Database

All persistent app data is stored in a Tanzu Postgres `db-small` instance (`greenplum-agent-db`). Schema is created and versioned by Flyway on first startup. Both app instances (`greenplum-ai-agent` and `greenplum-ai-agent-db`) share the same database.

### Tables

| Table | Purpose | Key Columns |
| :--- | :--- | :--- |
| `app_config` | Global app settings (currently: global prompt) | `key` (PK), `value`, `updated_at` |
| `allowed_users` | Email allowlist for access control | `email` (PK), `created_at` |
| `known_users` | All users who have ever logged in (admin autocomplete) | `email` (PK), `first_seen` |
| `roles` | Custom role names | `name` (PK), `created_at` |
| `user_roles` | Role assigned to each user | `email` (PK), `role`, `updated_at` |
| `user_config` | Per-user AI model and MCP settings (JSON) | `user_id` (PK), `config_json`, `updated_at` |
| `user_preferences` | Per-user personal AI instructions | `user_id` (PK), `prefs`, `updated_at` |
| `user_sessions` | Per-user chat session list and history (JSON) | `user_id` (PK), `data`, `updated_at` |
| `user_favourites` | Per-user saved favourite prompts (JSON array) | `user_id` (PK), `data`, `updated_at` |
| `user_memory` | Per-user, per-session LangChain4j chat memory | `user_id` + `session_id` (PK), `messages`, `updated_at` |
| `user_audit_log` | Server-side activity log (login, logout, query events) | `id` (PK), `email`, `action`, `ts` |

### Data Flow

Every user action updates the database immediately — no restart or manual export needed:

| User Action | Table Updated |
| :--- | :--- |
| Admin saves global prompt | `app_config` (upsert on `key = 'global_prompt'`) |
| Admin updates allowlist | `allowed_users` (full replace); cascades to delete removed users from `user_roles` |
| Admin adds / removes a role | `roles` |
| Admin assigns role to user | `user_roles` (upsert on `email`) |
| Admin deletes a user's role assignment | `user_roles` (delete by `email`) |
| User logs in for the first time | `known_users` (insert on conflict do nothing) |
| User logs in / logs out | `user_audit_log` (insert `LOGIN` / `LOGOUT`) |
| User sends a query | `user_audit_log` (insert `QUERY`) |
| User saves preferences | `user_preferences` (upsert on `user_id`) |
| User sends / receives a message | `user_memory` (upsert on `user_id + session_id`) |
| User saves / updates sessions | `user_sessions` (upsert on `user_id`) |
| User saves / deletes a favourite | `user_favourites` (upsert on `user_id`) |
| User clears chat memory | `user_memory` rows deleted for that `user_id` |

### Storage Mode Detection

`PostgresEnvironmentPostProcessor` runs before Spring Boot auto-configuration. It reads `VCAP_SERVICES` for a postgres binding and sets the `gp.agent.storage` property:

| Value | Active DAO | When |
| :--- | :--- | :--- |
| `postgres` | `PostgresAgentDao` | Tanzu Postgres service bound |
| `file` | `FileAgentDao` | No postgres binding (fallback) |

`@ConditionalOnProperty` on the DAO beans ensures exactly one implementation is loaded per startup.

### Flyway Migrations

Migrations live in `src/main/resources/db/` and are named `V{n}__{description}.sql`.

| Migration | Tables Created |
| :--- | :--- |
| `V1__schema.sql` | `app_config`, `allowed_users`, `known_users`, `roles`, `user_roles`, `user_config`, `user_preferences`, `user_sessions`, `user_favourites`, `user_memory` |
| `V2__audit_log.sql` | `user_audit_log` (with indexes on `ts` and `email`) |

Flyway runs automatically on every startup and applies any pending migrations. Completed migrations are tracked in `flyway_schema_history`.

### Data Retention

Automated nightly jobs purge old data to keep storage bounded:

| Data | Retention | Schedule (UTC) | Table |
| :--- | :--- | :--- | :--- |
| Audit log entries | 15 days | 02:15 | `user_audit_log` |
| Chat memory (AI context) | 30 days | 02:30 | `user_memory` |

Jobs run only when the app is in PostgreSQL mode (`gp.agent.storage=postgres`). Inactive sessions older than the threshold are removed silently — no user-visible impact.

### Multi-App Access

When multiple CF apps bind the same Tanzu Postgres service instance, each binding creates a unique database user. All binding users are granted full `SELECT / INSERT / UPDATE / DELETE` on all tables and sequences — changes made in one app are immediately visible in the other.

---

## Deployment

### Prerequisites

| Requirement | Notes |
| :--- | :--- |
| Java 17+ | Required at build time |
| Maven wrapper (`./mvnw`) | Included in the repo |
| CF CLI 7.x+ | For push and service operations |
| `java_buildpack_offline` v4.90+ | Resolves CredHub refs at container start |
| Tanzu SSO service instance | AuthHub `p-identity` — created by platform ops |
| Tanzu GenAI service instance | AI model — required for chat |
| Tanzu Postgres `db-small` | All persistent data — required |
| Greenplum MCP CF app | Separately deployed; see its README |

---

### Step 1 — Build

```bash
./mvnw clean package -DskipTests
# Produces: target/greenplum-ai-agent-1.0.0.jar
```

---

### Step 2 — Create platform services (ops team, once per environment)

#### 2a. Tanzu AuthHub SSO (`p-identity`)

The SSO service instance is created by the platform team in the Tanzu Apps Manager or via CLI:

```bash
# List available SSO plans:
cf marketplace -e p-identity

# Create the SSO service instance:
cf create-service p-identity <plan> gmd-authhub-sso
```

The OAuth2 client (redirect URIs, scopes) is configured by the Tanzu SSO operator in the Apps Manager portal for the `gmd-authhub-sso` service instance. Required settings:

| Setting | Value |
| :--- | :--- |
| **Grant types** | `authorization_code`, `refresh_token` |
| **Redirect URI** | `https://<app-route>/login/oauth2/code/sso` |
| **Scopes** | `openid`, `email`, `profile` |
| **Auto-approve** | `openid` |

#### 2b. Tanzu Postgres

```bash
# List available postgres plans:
cf marketplace -e postgres

# Create db-small instance (20 GB):
cf create-service postgres db-small greenplum-agent-db
```

Flyway will create all tables on first app startup — no manual schema setup needed.

#### 2c. AI Model

```bash
# List available plans:
cf marketplace -e ai-models

# Create from a Tanzu GenAI marketplace plan:
cf create-service ai-models <plan> <service-name>

# Example — paid plan:
cf create-service ai-models ai-gmd-paid ai-gmd-paid-svc

# OR a user-provided service for a custom OpenAI-compatible endpoint:
cf create-user-provided-service gmd-ai-prod-svc \
  -p '{
    "provider":   "openai",
    "baseUrl":    "https://vllm.internal/v1",
    "apiKey":     "sk-...",
    "modelName":  "qwen2.5:32b"
  }'
```

If `modelName` is omitted the app auto-discovers the first non-embedding model from the `/models` endpoint. The resolved model name is shown in the app header after login.

<details>
<summary><strong>Switching to a different AI model plan (optional)</strong></summary>

Use these steps whenever you need to upgrade to a new plan or swap model providers after the initial deploy.

**1. Browse available plans**

```bash
cf marketplace -e ai-models
```

**2. Create the new service instance**

```bash
cf create-service ai-models <new-plan> <new-service-name>
# Example:
cf create-service ai-models ai-gmd-paid ai-gmd-paid-svc
```

**3. Inspect credentials (optional)**

```bash
# Create a service key to view the endpoint URL and available models:
cf create-service-key <new-service-name> <key-name>
cf service-key <new-service-name> <key-name>
# Delete when done:
cf delete-service-key <new-service-name> <key-name>
```

> The key shows `credentials.endpoint.openai_api_base`. Call `GET <base-url>/models` to confirm available model IDs.

**4. Update `manifest.yml`** — replace the old service name with the new one in the `services:` list.

**5. Push the app**

```bash
cf push
# NOTE: cf push binds the new service but does NOT unbind the old one automatically.
```

**6. Unbind the old service**

```bash
# Verify what is currently bound:
cf services

# Unbind the old AI service:
cf unbind-service greenplum-ai-agent <old-service-name>
# Example:
cf unbind-service greenplum-ai-agent gmd-ai-prod-svc
```

**7. Restage**

```bash
cf restage greenplum-ai-agent
```

> `cf restage` (not a second `cf push`) is required — it rebuilds the droplet with the current bound services so `VCAP_SERVICES` is clean.

**8. Verify**

```bash
# Confirm only the new service is bound:
cf services

# Check model resolved in startup logs:
cf logs greenplum-ai-agent --recent | grep -i "CF mode\|model\|discover"

# Inspect VCAP to confirm old service is gone:
cf env greenplum-ai-agent | grep -A5 "ai-models"
```

The model name appears in the app header immediately after login.

**Troubleshooting — AI service**

| Symptom | Likely cause | Fix |
| :--- | :--- | :--- |
| Header still shows old model after push | Old service still bound — both appear in VCAP_SERVICES | `cf unbind-service` old service → `cf restage` |
| Model dot red after switch | CredHub credential not resolved — buildpack too old | Ensure `java_buildpack_offline` v4.90+; `cf restage` |
| Model shown as `null` or `unknown` at startup | Credential format not recognised | Run `cf env greenplum-ai-agent` and verify the credentials block; check startup logs for `[VCAP]` lines |
| Wrong model selected (e.g. an embedding model) | Multiple models returned by `/models`; wrong one picked first | Check startup logs for `[VCAP] discovered model:` to see which model was selected |
| `Service instance not found` on create | Plan name typo or plan not available in this org/space | Re-run `cf marketplace -e ai-models` to confirm the exact plan name |
| Old model still used after push | Two AI service bindings present; first one takes precedence | Unbind the old service and `cf restage` |

</details>

#### 2d. Greenplum MCP

```bash
# 1. Deploy the Greenplum MCP server as a separate CF app (see its own README)

# 2. Create a user-provided service pointing to it:
ENCODED_AUTH=$(echo -n "username:password" | base64)

cf create-user-provided-service gp-mcp-greenplum \
  -p "{
    \"type\": \"greenplum\",
    \"url\":  \"https://<your-mcp-app-route>/mcp\",
    \"auth\": \"Basic ${ENCODED_AUTH}\"
  }"
```

#### 2e. OpenMetadata MCP (optional)

```bash
cf create-user-provided-service gp-mcp-openmetadata \
  -p '{
    "type":  "openmetadata",
    "url":   "https://om-mcp.internal/mcp",
    "auth":  "Bearer <jwt>"
  }'
```

---

### Step 3 — Configure `manifest.yml`

```yaml
---
applications:
- name: greenplum-ai-agent
  path: target/greenplum-ai-agent-1.0.0.jar
  memory: 2G
  instances: 1
  buildpacks:
    - java_buildpack_offline
  env:
    SPRING_PROFILES_ACTIVE: cloud
    JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 17.+ } }'
    ADMIN_PIN: <your-admin-pin>
    PERMANENT_ADMIN_EMAILS: first.admin@your-company.com,second.admin@your-company.com
    # Block storage kept as backup — no longer used by the app (data migrated to postgres).
    # Uncomment AGENT_DATA_DIR only if rolling back to file-based storage.
    # AGENT_DATA_DIR: /mnt/gp-data
  services:
    - gmd-authhub-sso                   # Tanzu AuthHub SSO (p-identity)
    # - greenplum-agent-storage         # Block storage (backup only — data migrated to postgres)
    - gmd-ai-prod-svc                   # Tanzu GenAI or user-provided AI model
    - gp-mcp-greenplum                  # Greenplum MCP (user-provided)
    - greenplum-agent-db                # Tanzu Postgres db-small — all persistent data
    # - gp-mcp-openmetadata             # OpenMetadata MCP (optional)
```

> **Security**: Do not commit `manifest.yml` with a real `ADMIN_PIN`. Use CF environment variables or a secrets manager.

---

### Step 4 — Push

```bash
cf push
```

The app starts in `cloud` profile. `SsoEnvironmentPostProcessor` runs before Spring Boot auto-configuration, reads the `p-identity` VCAP binding, and injects the OAuth2 client properties. `VcapServicesConfig` reads the AI model and MCP credentials.

---

### Step 5 — Verify

```bash
# Check instance state:
cf app greenplum-ai-agent

# Tail startup logs:
cf logs greenplum-ai-agent --recent
```

Expected startup log markers:

```
[POSTGRES] DataSource configured from VCAP: jdbc:postgresql://...
[CF] CF mode active — provider=openai model=gpt-4o baseUrl=https://... mcp=greenplum
[MIGRATION] No block-storage data directory found — skipping migration
  (or, on first boot with block storage still bound:)
[MIGRATION] Starting block-storage → postgres migration from /var/vcap/data/<uuid>
[MIGRATION] Migration completed successfully
Started GreenplumAgentApplication in X seconds
```

---

### Step 6 — First login

Open the app URL → redirected to Broadcom Okta login → authenticates via Tanzu AuthHub → lands on the chat interface. The user's Broadcom email is shown in the header.

---

## Architecture

```
Browser  (index.html · app.js · style.css)
    │
    │  Boot — SSO identity + platform config
    ├── GET  /api/auth/status    → authenticated, userId, email, cfMode, model, mcpServers
    │         (Spring Security validates JWT; Spring redirects to /oauth2/authorization/sso if unauthenticated)
    │
    │  Chat
    ├── POST /api/chat           → response from LangChain4j agent
    │         │
    │         ▼  VcapServicesConfig reads VCAP_SERVICES at startup
    │     ChatController
    │         │
    │         ├── Greenplum bound ──► GreenplumAgent
    │         │                           └── GreenplumMcpTools ──► Greenplum MCP ──► Greenplum DB
    │         │
    │         ├── OpenMetadata bound ► OpenMetadataAgent
    │         │                           └── OpenMetadataMcpTools ──► OpenMetadata MCP
    │         │
    │         └── Both bound ──────► GreenplumAgent (with both tool sets)
    │
    │  Sessions & Memory
    ├── GET  /api/sessions/load
    ├── POST /api/sessions/save
    └── POST /api/memory/clear
    │
    │  User
    ├── GET  /api/user/prefs              → load per-user preferences
    ├── POST /api/user/prefs/save         → persist per-user preferences
    └── GET  /api/user/current-role       → role for current authenticated user (drives admin button visibility)
    │
    │  Admin (requires ADMIN_PIN hash)
    ├── POST /api/admin/verify
    ├── POST /api/admin/save              → global prompt
    ├── GET  /api/admin/allowlist
    ├── POST /api/admin/allowlist         → access control list
    ├── GET  /api/admin/roles             → list all roles
    ├── POST /api/admin/roles/save        → create a role
    ├── POST /api/admin/roles/delete      → delete a role
    ├── GET  /api/admin/user-roles        → all users with assigned roles
    ├── POST /api/admin/user-roles/save   → assign or update a user's role
    ├── POST /api/admin/user-roles/delete → delete a user's role assignment
    └── GET  /api/admin/audit             → paginated audit log (permanent admins only)
    │
    │  SSO
    ├── GET  /oauth2/authorization/sso → redirect to AuthHub
    ├── GET  /login/oauth2/code/sso   → AuthHub callback; Spring exchanges code for tokens
    └── GET  /logout                  → clear Spring session → redirect to / → re-triggers SSO
```

**Startup property injection (`SsoEnvironmentPostProcessor`):**

Runs as a Spring `EnvironmentPostProcessor` before auto-configuration. Reads `VCAP_SERVICES` for the `p-identity` binding and injects:

```
spring.security.oauth2.client.registration.sso.*  (client-id, client-secret, redirect-uri, scopes)
spring.security.oauth2.client.provider.sso.*      (authorization-uri, token-uri, user-info-uri, jwk-set-uri)
```

OIDC discovery (`issuer-uri`) is intentionally NOT set — it triggers an HTTP call at startup that times out in air-gapped environments.

**VCAP credential resolution:**

| Binding | Credential path |
| :--- | :--- |
| `p-identity` (SSO) | `credentials.{auth_domain, client_id, client_secret}` |
| Tanzu GenAI v2 | `credentials.endpoint.{openai_api_base, api_key, name}` |
| Tanzu GenAI v1 | `credentials.{api_url, api_key}` |
| User-provided AI | `credentials.{provider, baseUrl, apiKey, modelName}` |
| User-provided MCP | `credentials.{type, url, auth}` |
| Block storage | `volume_mounts[0].container_dir` (actual writable path) |

---

## Broadcom SSO Integration — Technical Deep Dive

This section describes exactly how the app integrates with Broadcom AuthHub (Tanzu SSO / UAA) using Spring Security OAuth2. Use this as a reference when implementing the same pattern in another Spring Boot CF app.

---

### Overview

Broadcom's enterprise SSO stack layers three systems:

```
Browser
  │  GET /oauth2/authorization/sso
  ▼
Spring Security (this app)
  │  Redirect to UAA Authorization Endpoint
  ▼
Tanzu AuthHub UAA          ← p-identity CF service binding
  │  Redirect to Okta (Broadcom IdP)
  ▼
mylogin.broadcom.com       ← Okta (SAML/OIDC to UAA)
  │  SAML assertion back to UAA
  ▼
UAA issues authorization code
  │  Redirect to /login/oauth2/code/sso
  ▼
Spring Security exchanges code → ID token + access token
  │  Extracts user_name / email from OIDC claims
  ▼
App — user authenticated; session created
```

The app is an **OAuth2 Authorization Code client** with UAA as the Authorization Server. UAA acts as a broker — it federates to the corporate Okta IdP and issues OIDC tokens back to the app.

---

### Components

#### 1. `SsoEnvironmentPostProcessor`

**File:** `src/main/java/com/gp/agent/SsoEnvironmentPostProcessor.java`

Runs as a Spring `EnvironmentPostProcessor` — before any `@Configuration` class or auto-configuration is evaluated. This is required because the OAuth2 client properties (`spring.security.oauth2.*`) must be present before Spring Security's `OAuth2ClientAutoConfiguration` wires its beans.

At startup it reads the `VCAP_SERVICES` environment variable, locates the `p-identity` service binding, and extracts three credentials: `auth_domain` (the UAA base URL), `client_id`, and `client_secret`. It then programmatically injects all required Spring Security OAuth2 properties into the Spring `Environment` before any bean is created — no `application.properties` entries needed for SSO config.

```java
@Override
public void postProcessEnvironment(ConfigurableEnvironment environment,
                                   SpringApplication application) {
    // 1. Read VCAP_SERVICES — find the p-identity (Tanzu SSO) binding
    String vcap = System.getenv("VCAP_SERVICES");
    // 2. Parse credentials: auth_domain, client_id, client_secret
    // 3. Inject Spring Security OAuth2 properties into the environment
}
```

**Properties injected:**

| Property | Value |
| :--- | :--- |
| `spring.security.oauth2.client.registration.sso.client-id` | `client_id` from VCAP |
| `spring.security.oauth2.client.registration.sso.client-secret` | `client_secret` from VCAP |
| `spring.security.oauth2.client.registration.sso.redirect-uri` | `{baseUrl}/login/oauth2/code/sso` |
| `spring.security.oauth2.client.registration.sso.scope` | `openid,email,profile` |
| `spring.security.oauth2.client.registration.sso.authorization-grant-type` | `authorization_code` |
| `spring.security.oauth2.client.provider.sso.authorization-uri` | `{auth_domain}/oauth/authorize` |
| `spring.security.oauth2.client.provider.sso.token-uri` | `{auth_domain}/oauth/token` |
| `spring.security.oauth2.client.provider.sso.user-info-uri` | `{auth_domain}/userinfo` |
| `spring.security.oauth2.client.provider.sso.jwk-set-uri` | `{auth_domain}/token_keys` |

**Why no `issuer-uri`?**
Setting `issuer-uri` triggers Spring Security to perform an OIDC Discovery HTTP call (`/.well-known/openid-configuration`) at startup. In air-gapped or network-restricted environments this call blocks for 30+ seconds and eventually causes a startup timeout. Providing all four endpoint URIs explicitly bypasses discovery entirely.

**Registration in `META-INF/spring.factories`:**

The class is registered as a Spring extension point via `META-INF/spring.factories` under the `org.springframework.boot.env.EnvironmentPostProcessor` key. Without this entry the class is never invoked and all OAuth2 properties remain unset at startup.

```properties
org.springframework.boot.env.EnvironmentPostProcessor=\
  com.gp.agent.SsoEnvironmentPostProcessor
```

---

#### 2. `SecurityConfig` (cloud profile only)

**File:** `src/main/java/com/gp/agent/SecurityConfig.java`  
**Active when:** `SPRING_PROFILES_ACTIVE=cloud` (set in `manifest.yml`)

##### Filter chain

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/css/**", "/js/**", "/img/**", "/images/**",
                             "/favicon.ico", "/error", "/access-denied.html").permitAll()
            .anyRequest().authenticated()
        )
        .oauth2Login(oauth2 -> oauth2
            .userInfoEndpoint(ui -> ui.oidcUserService(oidcUserService()))
            .defaultSuccessUrl("/", true)
            .failureUrl("/access-denied.html?error=true")
        )
        .logout(logout -> logout
            .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
            .logoutSuccessHandler((req, res, auth) -> {
                String authDomain = readAuthDomain();
                res.sendRedirect(authDomain != null ? authDomain + "/logout.do" : "/");
            })
            .invalidateHttpSession(true)
            .clearAuthentication(true)
            .deleteCookies("JSESSIONID")
        )
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));
    return http.build();
}
```

Key decisions:
- Static assets (`/css/**`, `/js/**`, etc.) are **permitted without auth** so the browser can load the page shell before the SSO redirect resolves.
- `/api/**` is CSRF-exempt because the JavaScript frontend uses `fetch()` with JSON bodies — no form-based state mutation.
- `defaultSuccessUrl("/", true)` ensures post-login always lands on the home page, not on whatever the Spring session saved as the pre-redirect URL.

##### OIDC user service — allowlist check

After Spring Security completes the token exchange it calls a custom `OidcUserService` to load the user profile. This service extracts the user's email — UAA places the Broadcom email in the `user_name` claim rather than the standard `email` claim, so the code tries `user_name` first and falls back to `email` if absent. It then runs the allowlist check; if the user is not permitted, authentication is rejected immediately with `access_denied`. On success the email is appended to `known-users.txt` for admin autocomplete.

```java
@Bean
OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
    OidcUserService delegate = new OidcUserService();
    return userRequest -> {
        OidcUser user = delegate.loadUser(userRequest);

        // Resolve email: UAA puts it in "user_name" claim, fallback to standard "email"
        String email = user.getAttribute("user_name");
        if (email == null || email.isBlank()) email = user.getEmail();
        if (email == null) email = user.getName();

        if (!isUserAllowed(email)) {
            throw new OAuth2AuthenticationException(
                new OAuth2Error("access_denied", "Not authorized.", null));
        }
        recordKnownUser(email);   // Append to known-users.txt for admin autocomplete
        return user;
    };
}
```

**Why `user_name` instead of `email`?** UAA's OIDC token puts the Broadcom email address in the `user_name` claim, not in the standard `email` claim. The standard `email` claim may be absent or contain a different value depending on the federation mapping.

##### Allowlist check

The allowlist is stored in `allowed-users.txt` on block storage — one email per line, `#` lines are comments. The file is read fresh on every login, so adding or removing a user takes effect immediately without restarting the app. If the file does not exist or contains no active entries the check is fail-open (all authenticated users are allowed). Email comparison is case-insensitive.

```java
static boolean isUserAllowed(String email) {
    File allowlistFile = new File(resolveDataDir(), "allowed-users.txt");
    if (!allowlistFile.exists()) return true;   // fail-open: no file = allow all
    List<String> lines = Files.readAllLines(allowlistFile.toPath(), UTF_8);
    boolean hasEntries = lines.stream()
        .anyMatch(l -> !l.trim().isEmpty() && !l.trim().startsWith("#"));
    if (!hasEntries) return true;              // empty file = allow all
    return lines.stream()
        .map(String::trim)
        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
        .anyMatch(l -> l.equalsIgnoreCase(email));
}
```

##### Logout flow

Standard OIDC RP-Initiated Logout (`/logout?post_logout_redirect_uri=...`) fails in this setup because UAA chains the logout to Okta, which rejects the `post_logout_redirect_uri` if it is not pre-registered in Okta's allowed list (a platform-ops constraint beyond the app's control).

The working approach:

```
Browser  →  GET /logout
Spring Security  — invalidate session, delete JSESSIONID cookie
  └──  redirect to  {auth_domain}/logout.do
UAA  — invalidate its own session for this user
  └──  redirect to UAA login page
Browser lands on mylogin.broadcom.com / UAA login
```

UAA's `/logout.do` endpoint (not the OIDC-standard `/logout`) clears the UAA session without requiring a registered redirect URI. This is a UAA-specific URL that bypasses the Okta registration constraint.

---

#### 3. `DevSecurityConfig` (non-cloud profile)

**File:** `src/main/java/com/gp/agent/DevSecurityConfig.java`  
**Active when:** no `cloud` profile (local development)

All requests are permitted without authentication.

```java
@Configuration
@Profile("!cloud")
public class DevSecurityConfig {
    @Bean
    SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/**"))
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessUrl("/")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );
        return http.build();
    }
}
```

The `/logout` endpoint still exists and redirects to `/` so the Sign Out button works consistently in both profiles. In local dev mode `userId` defaults to `local-dev-user`.

---

#### 4. `GET /api/auth/status` — Client bootstrap

After page load the JavaScript calls this endpoint to learn the authenticated identity and platform configuration.

It returns a JSON object with `authenticated`, `userId` (the UAA `sub` UUID — stable per-user, used as the block storage directory key), `email` (resolved from the `user_name` claim), and — when running on CF — `cfMode`, `model`, and `mcpServers`. In local dev mode (no `cloud` profile) it returns `userId: "local-dev-user"` so all features work without SSO.

```java
@GetMapping("/auth/status")
ResponseEntity<Map<String, Object>> authStatus(Authentication authentication) {
    Map<String, Object> response = new LinkedHashMap<>();
    if (vcapConfig.isCfMode()) {
        response.put("cfMode",     true);
        response.put("model",      vcapConfig.getModelSummary());
        response.put("mcpServers", vcapConfig.getMcpServerNames());
    }
    if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
        String userId = oidcUser.getSubject();   // stable UAA UUID — used as user ID
        String email  = oidcUser.getAttribute("user_name");
        if (email == null || email.isBlank()) email = oidcUser.getEmail();
        response.put("authenticated", true);
        response.put("userId", userId);
        response.put("email", email);
    } else {
        response.put("authenticated", true);
        response.put("userId", "local-dev-user");
        response.put("email", "local-dev");
    }
    return ResponseEntity.ok(response);
}
```

---

### Registering the App on the Broadcom SSO Portal

Before the app can authenticate users, an OAuth2 client registration must be created in the Tanzu SSO service. This is a one-time setup step performed in Tanzu Apps Manager.

#### Step-by-step

1. **Log in to Tanzu Apps Manager**
   Navigate to the Apps Manager UI for your foundation (e.g. `https://apps.<your-cf-domain>`).

2. **Select your org and space**
   Switch to the org and space where the app will run (e.g. `gmd` / `ai-agent`).

3. **Open the SSO service instance**
   Go to **Services** in the left sidebar. Click on the `p-identity` (or `gmd-authhub-sso`) service instance that is bound to your app.

4. **Click "Manage"**
   This opens the Tanzu SSO configuration portal for the service instance.

5. **Navigate to Apps**
   In the SSO portal, click **Apps** in the top navigation.

6. **Create a new app**
   Click **New App** (or **+ Add App**).

7. **Fill in the app registration form**

   | Field | Value |
   | :--- | :--- |
   | **App Name** | `greenplum-ai-agent` (any descriptive label) |
   | **App Type** | `Web App` |
   | **Redirect URIs** | `https://<your-app-route>/login/oauth2/code/sso` |
   | **Grant Types** | `authorization_code`, `refresh_token` |
   | **Scopes** | `openid`, `email`, `profile` |
   | **Auto-Approve Scopes** | `openid` |
   | **Token lifetime** | Default (3600 s) |

   > Replace `<your-app-route>` with the CF route assigned to your app, e.g. `greenplum-ai-agent.apps.tanzu.broadcom.net`.

8. **Save**
   Click **Save** (or **Submit**). The SSO portal generates a `client_id` and `client_secret` and stores them in the bound service credentials — your app reads them automatically from `VCAP_SERVICES` at startup via `SsoEnvironmentPostProcessor`. No manual copy-paste of credentials is needed.

9. **Verify the binding**
   Run `cf env <app-name>` and confirm the `p-identity` credentials block contains `auth_domain`, `client_id`, and `client_secret`.

> **Redirect URI must match exactly.** Spring Security registers the callback path as `/login/oauth2/code/sso` (registration ID = `sso`). Any mismatch — trailing slash, wrong scheme, wrong host — causes a redirect-loop or "Redirect URI mismatch" error from UAA.

---

### Required Maven Dependencies

```xml
<!-- Spring Security OAuth2 client (handles Authorization Code flow, token exchange) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

No additional UAA-specific libraries are required. Spring Security's standard OAuth2 client handles the full Authorization Code + PKCE flow with any compliant OAuth2/OIDC server including UAA.

---

### End-to-End Login Sequence

```
1.  Browser  →  GET https://<app>/
2.  Spring Security  →  302 to /oauth2/authorization/sso
3.  Spring Security  →  302 to {auth_domain}/oauth/authorize
                          ?client_id=<id>
                          &redirect_uri=https://<app>/login/oauth2/code/sso
                          &response_type=code
                          &scope=openid email profile
                          &state=<csrf-token>
4.  UAA  →  302 to https://mylogin.broadcom.com (Okta)
5.  User authenticates with Broadcom credentials at mylogin.broadcom.com
6.  Okta  →  SAML assertion  →  UAA
7.  UAA  →  302 to https://<app>/login/oauth2/code/sso?code=<code>&state=<state>
8.  Spring Security  →  POST {auth_domain}/oauth/token (code exchange)
9.  UAA  →  { id_token, access_token, refresh_token }
10. Spring Security  →  GET {auth_domain}/userinfo  (load profile claims)
11. oidcUserService()  →  allowlist check (isUserAllowed)
12. Success  →  302 to /
13. Browser  →  GET /api/auth/status  →  { authenticated:true, userId, email, cfMode, … }
14. App boots with user identity set
```

### End-to-End Logout Sequence

```
1.  Browser JS  →  clears all gp_* localStorage keys
2.  Browser  →  GET https://<app>/logout
3.  Spring Security  →  invalidate HttpSession, delete JSESSIONID cookie
4.  logoutSuccessHandler  →  302 to {auth_domain}/logout.do
5.  UAA  →  invalidates UAA session for this user
6.  UAA  →  302 to UAA login page / Broadcom login page
7.  User must re-authenticate on next visit to the app
```

---

### Common Integration Pitfalls

| Pitfall | Root Cause | Fix |
| :--- | :--- | :--- |
| Startup timeout / hang | `issuer-uri` set — triggers OIDC discovery HTTP call | Remove `issuer-uri`; inject all four endpoint URIs explicitly |
| "Post Logout Redirect URI not allowed" | OIDC RP-Initiated Logout passes a redirect URI that Okta hasn't approved | Use UAA `/logout.do` instead of `/logout?post_logout_redirect_uri=...` |
| Redirect loop after login | Redirect URI in App Manager doesn't match `/login/oauth2/code/sso` exactly | Update to match Spring Security's default path |
| `user_name` claim empty | Falling back to `email` claim which UAA may not populate | Always try `user_name` first; Broadcom UAA sets email there |
| `Authentication` is `null` in controllers | Cloud profile not active (`SPRING_PROFILES_ACTIVE` not set) | Set `SPRING_PROFILES_ACTIVE: cloud` in `manifest.yml` |
| OAuth2 beans missing | `SsoEnvironmentPostProcessor` not registered in `spring.factories` | Verify `META-INF/spring.factories` entry exists in the JAR |
| User passes allowlist but gets 403 | `allowed-users.txt` has case-mismatch | Allowlist check is case-insensitive (`equalsIgnoreCase`) — check file encoding |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| :--- | :--- | :--- |
| Redirect loop on login | SSO service not bound or credentials malformed | `cf bind-service` + `cf restage`; check startup logs for `[SSO]` markers |
| "Access Denied" after login | User email not in allowlist | Add email via the Admin Panel, or clear the allowlist (empty table = allow all) |
| Model dot red on startup | AI service not bound or credential resolution failed | Bind the AI service and `cf restage`; check logs for `[CF] CF mode active` |
| Greenplum dot green but queries fail | Port 5432 blocked by firewall | Open firewall from the MCP app to the database; the dot only checks MCP HTTP reachability, not database connectivity |
| App crashes on startup with `permission denied for table flyway_schema_history` | Multiple apps share the same postgres instance; table owner user differs from connecting user | Grant `ALL PRIVILEGES ON ALL TABLES IN SCHEMA public` to the connecting user from the table owner user; see [Multi-App Access](#multi-app-access) |
| App crashes on startup with `ERROR: relation "..." does not exist` | Flyway migration did not run or failed silently | Check logs for `[Flyway]` errors; ensure `flyway-database-postgresql` dependency is present in `pom.xml` for Spring Boot 3.3+ |
| Admin changes (prompt, allowlist, roles) not persisted after restage | App running in file mode instead of postgres mode | Check startup logs for `[POSTGRES] DataSource configured` — if absent, verify `greenplum-agent-db` is bound and `cf restage` |
| Old UI after deploy | Browser cached previous CSS / JS | Hard-refresh: Cmd/Ctrl + Shift + R |
| User preferences not applying | Postgres binding missing or app in file mode | Verify `greenplum-agent-db` is bound; check `gp.agent.storage` in startup logs |
| "Error connecting to backend API" with a specific message | Server-side error (e.g. SQL parse failure, model timeout) | Read the error detail — it shows the exact failure returned by the server |
| Sessions lost after restage | Expected behaviour if app was in file mode before migration | Verify data was migrated to postgres; check `user_sessions` table has rows |

### Logs

```bash
cf logs greenplum-ai-agent --recent   # startup + binding resolution
cf logs greenplum-ai-agent            # live tail
```

### Key log markers

| Prefix | Meaning |
| :--- | :--- |
| `[POSTGRES] DataSource configured from VCAP` | Postgres binding found; app will use `PostgresAgentDao` |
| `[CF] CF mode active` | VCAP_SERVICES parsed; model + MCP config resolved |
| `[SSO] Login attempt by` | User authenticated; allowlist check running |
| `[SSO] Access granted for` | User passed allowlist check |
| `[SSO] Access denied for` | User not in allowlist |
| `[MIGRATION] Starting block-storage → postgres migration` | First boot with block storage present; auto-migrating files to DB |
| `[MIGRATION] ... already in DB — skipping` | Data already migrated; migration skipped safely |
| `[MIGRATION] Migration completed successfully` | All file data copied to postgres |
| `[VCAP] discovered model:` | Model selected via `/models` auto-discovery |
