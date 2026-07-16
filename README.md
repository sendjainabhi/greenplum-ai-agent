# Greenplum AI Analytics Agent

> [!WARNING]
> **PROOF OF CONCEPT — Not for production use.**

A Cloud Foundry-native AI assistant for the Tanzu data platform. Authenticate via Broadcom AuthHub SSO, bind your Tanzu GenAI model, Greenplum MCP, and block-storage services — users sign in with their Broadcom credentials and start chatting with their Greenplum database in natural language. No UI configuration required.

---

## Table of Contents

1. [Gallery](#gallery)
2. [How It Works](#how-it-works)
3. [Platform Services](#platform-services)
4. [Deployment](#deployment)
5. [Admin Panel](#admin-panel)
6. [MCP Capabilities](#mcp-capabilities)
7. [Example Prompts](#example-prompts)
8. [Features](#features)
9. [Architecture](#architecture)
10. [Troubleshooting](#troubleshooting)

---

## Gallery

| Chat Interface | MCP Tool Execution |
| :---: | :---: |
| ![Dashboard](img/img1.jpeg) | ![Execution](img/img2.jpeg) |
| **Cluster Performance** | **Query Results** |
| ![Performance](img/img3.jpeg) | ![Results](img/img4.jpeg) |

---

## How It Works

1. User opens the app URL → redirected to Broadcom AuthHub SSO (Okta) for authentication
2. On successful login the JWT `sub` claim becomes the user ID for per-user session isolation
3. At startup the app reads all AI model, MCP, and storage credentials from `VCAP_SERVICES` — nothing to configure manually
4. Admins can restrict access to specific Broadcom email addresses via the Admin Panel allowlist
5. Each user's sessions, AI memory, and saved prompts are stored independently on block storage

**Header status indicators:**

| Indicator | Meaning |
| :--- | :--- |
| **Model dot** | Green when AI model endpoint is reachable |
| **Greenplum dot** | Green when Greenplum MCP server HTTP is reachable |
| **OpenMetadata dot** | Shown only when that service is bound; green/red per connection |

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
| `auth_domain` / `auth-domain` | AuthHub UAA base URL (e.g. `https://gmd-authhub.login.sys-hyperion.tanzu.broadcom.net`) |
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

### Block Storage (`greenplum-agent-storage`)

CF block storage volume service. Stores per-user session files, AI memory, saved favourites, the admin global prompt, and the access control allowlist. The app reads the actual mount path from `VCAP_SERVICES → volume_mounts[*].container_dir` — the `AGENT_DATA_DIR` env var is only a hint.

```bash
# Create block storage (ops team, once per environment):
cf create-service <block-storage-service> <plan> greenplum-agent-storage
```

### Greenplum MCP (`gp-mcp-greenplum`)

User-provided service pointing to a running Greenplum MCP CF app.

```bash
cf create-user-provided-service gp-mcp-greenplum \
  -p '{
    "type":  "greenplum",
    "url":   "https://gp-mcp-server.apps.tanzu.broadcom.net/mcp",
    "auth":  "Basic <base64-of-username:password>"
  }'
```

> The MCP server is a separately deployed CF app. The green dot in the header indicates the MCP HTTP endpoint is reachable — not necessarily that the Greenplum database port (5432) is open. Database queries require the firewall to permit traffic from the MCP app to the database.

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
| Block storage service instance | Per-user persistence — highly recommended |
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

#### 2b. Block Storage

```bash
# Create using your environment's block storage broker:
cf create-service <block-storage-broker> <plan> greenplum-agent-storage
```

#### 2c. AI Model

```bash
# Tanzu GenAI marketplace service:
cf create-service ai-models standard gmd-ai-prod-svc

# OR a user-provided service for a custom OpenAI-compatible endpoint:
cf create-user-provided-service gmd-ai-prod-svc \
  -p '{
    "provider":   "openai",
    "baseUrl":    "https://vllm.internal/v1",
    "apiKey":     "sk-...",
    "modelName":  "qwen2.5:32b"
  }'
```

#### 2d. Greenplum MCP

```bash
# 1. Deploy the Greenplum MCP server as a separate CF app (see its own README)

# 2. Create a user-provided service pointing to it:
ENCODED_AUTH=$(echo -n "username:password" | base64)

cf create-user-provided-service gp-mcp-greenplum \
  -p "{
    \"type\": \"greenplum\",
    \"url\":  \"https://gp-mcp-server.apps.tanzu.broadcom.net/mcp\",
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
    AGENT_DATA_DIR: /mnt/gp-data        # hint only; actual path read from VCAP volume_mounts
  services:
    - gmd-authhub-sso                   # Tanzu AuthHub SSO (p-identity)
    - greenplum-agent-storage           # CF block storage for persistence
    - gmd-ai-prod-svc                   # Tanzu GenAI or user-provided AI model
    - gp-mcp-greenplum                  # Greenplum MCP (user-provided)
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
[CF] CF mode active — provider=openai model=gpt-4o baseUrl=https://... mcp=greenplum
DATA DIR      : /var/vcap/data/<uuid>
LOG FILE      : /var/vcap/data/<uuid>/greenplum-agent.log
```

---

### Step 6 — First login

Open the app URL → redirected to Broadcom Okta login → authenticates via Tanzu AuthHub → lands on the chat interface. The user's Broadcom email is shown in the header.

---

## Admin Panel

Access: click the **🔐** button in the header and enter the `ADMIN_PIN`.

### Global Pre-Training Prompt

A system instruction prepended to every chat request for every user. Useful for enforcing data governance rules, restricting topic scope, or providing database context.

- Click **✏️ Edit** to modify, **Save** to apply immediately (no restart needed)
- Leave blank to disable

### Access Control — Allowed Users

File-based email allowlist stored at `{data-dir}/allowed-users.txt`.

| State | Behaviour |
| :--- | :--- |
| File absent or empty | **All** authenticated Broadcom users are allowed (fail-open) |
| File has entries | Only listed email addresses are admitted; others get a 403 |

**UI actions:**
- Type an email address and click **Add** (or press Enter) — validates format and prevents duplicates; saves automatically
- Click **👁 Show User List** to view current entries
- Click **✕** next to any entry to remove it — saves automatically

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

## Features

### 🔐 Authentication — Broadcom AuthHub SSO

- **OIDC login** via Tanzu `p-identity` service — redirects to Broadcom Okta; no passwords stored in the app
- **JWT `sub` as user ID** — sessions and files are isolated per authenticated identity
- **Allowlist** — admin can restrict access to specific Broadcom email addresses
- **Sign out** — clears the Spring session; next visit forces a fresh SSO flow

### 💬 Chat & Sessions

- **Up to 10 concurrent sessions** — independent tabs with separate AI memory and titles
- **Full session persistence** — saved to block storage; restored on next login from any browser
- **30-message context window** per session
- **Auto-title** — first message becomes the tab title
- **Session rename / delete** — inline from the sidebar
- **Cancel in-flight** — Cancel button aborts a running model request immediately
- **Prompt autocomplete** — debounced suggestions from prompt history as you type

### 📄 PDF Export

- **One-click export** — `⬇ Export PDF` below every AI response
- **Page-break safe** — tables, code blocks, and charts never split across pages (`page-break-inside: avoid`)
- **Charts included** — canvas charts converted to PNG snapshots before rendering
- **Greenplum branding** — forest-green header with SVG logo
- **Auto filename** — `greenplum-{query-slug}-{YYYY-MM-DD}.pdf`

### ⭐ Saved Favourites

Save any prompt with a label for quick reuse. Accessible from the sidebar; persisted on block storage.

### 🔐 Admin Panel

- Global pre-training prompt — applies to all users, takes effect immediately
- Email-based access control allowlist — add/remove users, view current list
- Protected by `ADMIN_PIN` environment variable

### 🎨 UI

- **Dark / Light mode** — toggle persisted server-side per user
- **Theme-aware** — all modals, inputs, and status indicators adapt to the selected theme
- **Compact header** — initials avatar, icon-only action buttons (tooltips on hover), status dots
- **Settings hidden in CF mode** — configuration panel only visible in local dev; hidden when `VCAP_SERVICES` is present

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
    │  Admin (requires ADMIN_PIN hash)
    ├── POST /api/admin/verify
    ├── POST /api/admin/save         → global prompt
    ├── GET  /api/admin/allowlist
    └── POST /api/admin/allowlist    → access control list
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

## Troubleshooting

| Symptom | Likely cause | Fix |
| :--- | :--- | :--- |
| Redirect loop on login | SSO service not bound or `p-identity` credentials malformed | `cf bind-service` + `cf restage`; check startup for `[SSO]` log lines |
| "Access Denied" after login | User email not in allowlist | Add email in Admin Panel or clear the allowlist to allow all |
| Model dot red on startup | AI service not bound or CredHub resolution failed | `cf bind-service` + `cf restage`; check for `[CF] CF mode active` log |
| Greenplum dot green but queries fail | Port 5432 blocked by firewall | Open firewall from MCP app to DB; dot only checks MCP HTTP, not DB port |
| Allowlist save error (`/mnt/gp-data/...`) | Block storage not mounted at `AGENT_DATA_DIR` path | Bind `greenplum-agent-storage`; app reads actual path from `volume_mounts` |
| Settings button visible in CF | `cfMode` detection failed | Ensure `VCAP_SERVICES` is set; check `VcapServicesConfig` init logs |
| Sessions lost after restage | Block storage not bound | Bind `greenplum-agent-storage` and restage |
| PDF empty for follow-up messages | (Fixed in v55+) duplicate `msg-` IDs caused wrong element capture | Hard-refresh to load latest `app.js` |
| Old UI after deploy | Browser cached old CSS / JS | Hard-refresh: Cmd/Ctrl + Shift + R |

### Logs

```bash
cf logs greenplum-ai-agent --recent   # startup + binding resolution
cf logs greenplum-ai-agent            # live tail
```

### Key log markers

| Prefix | Meaning |
| :--- | :--- |
| `[CF] CF mode active` | VCAP_SERVICES parsed; model + MCP config resolved |
| `[SSO] Login attempt by` | User authenticated; allowlist check running |
| `[SSO] Access granted for` | User passed allowlist check |
| `[SSO] Access denied for` | User not in allowlist |
| `DATA DIR :` | Resolved writable data directory (from volume_mounts or AGENT_DATA_DIR) |
| `[VCAP] discovered model:` | Model selected via `/models` auto-discovery |
