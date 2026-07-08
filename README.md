# Greenplum AI Agent

> [!WARNING]
> **PROOF OF CONCEPT — Not for production use.**

An intelligent, read-only AI assistant that connects your data infrastructure to a Large Language Model through the Model Context Protocol (MCP). Supports **Greenplum database**, **OpenMetadata data catalog**, or **both simultaneously**. Chat in natural language and get results in a polished, persistent chat interface.

---

## Table of Contents

1. [Gallery](#gallery)
2. [Features](#features)
3. [Prerequisites](#prerequisites)
4. [Quick Start](#quick-start)
5. [First-Time Setup](#first-time-setup)
6. [Configuration Reference](#configuration-reference)
7. [Data Platform Modes](#data-platform-modes)
8. [Button Reference](#button-reference)
9. [Data Management](#data-management)
10. [Cloud Foundry Deployment](#cloud-foundry-deployment)
11. [Architecture](#architecture)
12. [Troubleshooting](#troubleshooting)

---

## Gallery

| Chat Interface | MCP Tool Execution |
| :---: | :---: |
| ![Dashboard](img/img1.jpeg) | ![Execution](img/img2.jpeg) |
| **Cluster Performance** | **Query Results** |
| ![Performance](img/img3.jpeg) | ![Results](img/img4.jpeg) |
| ![Connectivity](img/img5.jpeg) | |

---

## Features

### 🔒 Authentication & Security

- **PIN-based accounts** — username + PIN (min 4 chars) with an optional hint; plain PIN never stored on disk
- **SHA-256 hashing** — only the PIN hash is written to `users/{id}/config.json`
- **Server-side auth** — every login is verified server-side; browser cache is never the authority
- **Same browser** — subsequent visits require PIN only; username already remembered
- **New browser / incognito** — always prompts for username + PIN; server verifies against the stored account
- **Forgot PIN** — shows hint and the option to reset the account (deletes all data, forces fresh setup)
- **Change PIN** — dedicated modal accessible via a link in ⚙️ Settings; old PIN verified before new one is accepted
- **Admin PIN** — separate PIN (`ADMIN_PIN` env var) guards the global Admin Panel

### 💬 Chat & Sessions

- **Up to 10 concurrent sessions** — independent chat tabs, each with its own AI memory and title
- **Full session persistence** — sessions, titles, messages, and timestamps saved to the server; restored on any browser after sign-in
- **AI memory per session** — separate LangChain4j memory chain per tab; 30-message window; 90-day retention
- **Auto-title** — first message of each session becomes the tab title automatically
- **Session rename** — click ✏️ on any sidebar tab to rename inline
- **Session delete** — click 🗑️ to remove a session and its AI memory from the server
- **Cancel in-flight** — a **Cancel** button replaces Send while a request is running; aborts immediately
- **Prompt autocomplete** — debounced suggestions from your prompt history appear as you type

### 📄 PDF Export

- **One-click export** — `⬇ Export PDF` button below every AI response
- **Greenplum branding** — SVG logo, forest-green header, query callout box
- **Dark-mode safe** — `data-theme` removed before render so text is always visible on white paper
- **Charts included** — canvas charts converted to PNG snapshots before PDF render
- **Auto filename** — `greenplum-{query-slug}-{YYYY-MM-DD}.pdf`

### ⭐ Saved Favourites

- **Save any prompt** — click `⭐ Favourite` below any message you sent; give it a label
- **Sidebar access** — **⭐ Saved Favourites** panel in the left sidebar; click to fill the input
- **Server persistence** — stored in `users/{id}/favourites.json`; survives chat clears and browser changes
- **Individual delete** — 🗑️ next to each favourite removes it without affecting others

### 🔐 Admin Panel

- **Global pre-training prompt** — silently prepended to every user's chat request; takes effect immediately on save
- **PIN-protected** — requires the separate admin PIN to open
- **All-user scope** — individual user system prompts are appended after the global prompt
- **Disable** — leave the global prompt blank and save to turn it off

### 🔌 Multi-Provider LLM Support

- **Ollama** — local inference via `http://localhost:11434`; no API key required
- **OpenAI-compatible** — works with ChatGPT, vLLM, LMStudio, and any OpenAI-spec endpoint
- **Anthropic** — Claude Sonnet, Claude Opus, and other Claude models via the Anthropic API
- **Hot-swap** — switch provider, model, and endpoint from ⚙️ Settings without restarting the server

### 🐘 Greenplum MCP Capabilities

- **`checkTableBloat`** — identifies tables with excessive dead tuples; recommends `VACUUM`
- **`getClusterStatus`** — checks segment status, mirroring health, and replication state
- **`executeQuery`** — executes safe `SELECT` queries; hard-blocked from `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`
- **Schema verification** — always introspects `information_schema.columns` before querying a table
- **Thinking block strip** — removes internal reasoning blocks from Qwen3 / DeepSeek-R1

### 📂 OpenMetadata MCP Capabilities *(New)*

Connect to an OpenMetadata data catalog via MCP and ask questions in natural language:

- **`searchAssets`** — keyword search across all asset types (tables, dashboards, pipelines, topics, ML models, glossary terms, domains)
- **`getEntityDetails`** — full metadata for any entity: columns, tags, owners, descriptions, service details
- **`getLineage`** — upstream sources and downstream consumers for any entity (3 hops by default)
- **`listEntities`** — overview of all entities of a given type (databases, services, tables, domains, etc.)
- **`getDataQualityResults`** — data quality test results for a table: test name, status, last run, failure reason
- **`rootCauseAnalysis`** — analyzes upstream failures and downstream impact for a specific entity

**Auth support:** Bearer JWT tokens (OpenMetadata default) and Basic auth. Paste a raw JWT — `Bearer ` is prepended automatically.

### 🔀 Data Platform Mode Selector *(New)*

Choose how the agent connects from ⚙️ Settings → Data Platform:

| Mode | Description |
| :--- | :--- |
| **Greenplum** | Connects to Greenplum only (default) |
| **OpenMetadata** | Connects to OpenMetadata catalog only |
| **Both** | Connects to both simultaneously; agent picks the right tool per question |

- Mode is saved per user; a badge in the header shows the active mode
- Old config files without `activeMode` are auto-detected based on which MCP URLs are present
- Test Connection shows a separate status line for each MCP server, color-coded green/red

### 💡 Example Prompts

**Greenplum:**

| Prompt | MCP Tool |
| :--- | :--- |
| "Check bloat in the 'sales' table" | `checkTableBloat` |
| "Show cluster status" | `getClusterStatus` |
| "List all tables in the finance schema" | `executeQuery` |
| "What indexes exist on the orders table?" | `executeQuery` |
| "Show me the top 10 largest tables by size" | `executeQuery` |
| "Are there any down segments in the cluster?" | `getClusterStatus` |

**OpenMetadata:**

| Prompt | MCP Tool |
| :--- | :--- |
| "What data services are available in the catalog?" | `listEntities` |
| "Show me all tables in the telco schema" | `searchAssets` |
| "What columns does the sales table have?" | `searchAssets` → `getEntityDetails` |
| "Show lineage for the orders table" | `getLineage` |
| "Are there data quality tests for the customers table?" | `getDataQualityResults` |
| "Do a root cause analysis on the pipeline failure" | `rootCauseAnalysis` |
| "What depends on the raw_events table?" | `getLineage` |

---

## Prerequisites

| Requirement | Version / Notes |
| :--- | :--- |
| **Java** | JDK 17 or higher |
| **Maven** | 3.8+ (a `mvnw` wrapper is included — no separate install required) |
| **Greenplum MCP Server** | Required when mode = `greenplum` or `both` |
| **OpenMetadata MCP Server** | Required when mode = `openmetadata` or `both` (port 8080 by default) |
| **LLM Engine** | Ollama (local) **or** an OpenAI-compatible / Anthropic API key |

---

## Quick Start

### 1. Clone
```bash
git clone https://github.com/sendjainabhi/greenplum-ai-agent.git
cd greenplum-ai-agent
```

### 2. (Optional) Pull a local model
```bash
ollama pull qwen3:30b
ollama serve
```

### 3. Build and run
```bash
chmod +x start.sh stop.sh
./start.sh
```

`start.sh` does four things automatically:

| Step | What happens |
| :--- | :--- |
| **1. Build** | Runs `mvn clean package -DskipTests` |
| **2. Replace JAR** | Removes the old JAR from the project root; copies the fresh build from `target/` |
| **3. Stop old instance** | Gracefully terminates any previously running agent (by PID file) |
| **4. Start daemon** | Launches the new JAR in the background; writes PID and log path |

Stop at any time:
```bash
./stop.sh
```

### 4. Open the app
```
http://localhost:8080
```

---

## First-Time Setup

### Step 1 — Create an account

1. Open `http://localhost:8080` — the Sign In screen appears
2. Click **"Don't have an account? Create one"**
3. Enter a username — letters, numbers, `-` and `_` only (no `@`, `.`, or spaces)
4. Set a PIN (min 4 characters) and an optional hint
5. Click **Create PIN** — account is created and the app loads

> **Returning to the same browser:** The app remembers your username — you only need your PIN.
> **New browser or incognito:** Enter username and PIN — the server verifies against your stored account.

### Step 2 — Configure AI provider and data platform

1. Click **⚙️ Settings** in the header
2. Upload a credential file or fill in the fields manually
3. Set: LLM Provider, Endpoint URL, API Key, Model Name
4. Choose **Data Platform** mode (Greenplum / OpenMetadata / Both)
5. Fill in the MCP Server URL(s) and auth token(s) for the selected mode
6. Click **Test Connection** — green = success, red = failure, with per-service status lines
7. Click **Save Settings** — configuration is saved to the server

Settings persist across browsers, incognito windows, and restarts.

---

## Configuration Reference

### Credential file format

Upload a `.txt` or `.properties` file via ⚙️ Settings → Upload Config File. All fields are optional except `modelName`.

```properties
# ── 1. AI Provider ────────────────────────────────────────────────
# Options: ollama | openai | anthropic
provider=ollama

# ── 2. Model Name ─────────────────────────────────────────────────
# Ollama:    qwen3:30b, llama3, mistral
# OpenAI:    gpt-4o, gpt-4-turbo
# Anthropic: claude-3-5-sonnet-20241022
modelName=qwen3:30b

# ── 3. Base URL ───────────────────────────────────────────────────
# Ollama (required): http://localhost:11434
# OpenAI (optional): https://api.openai.com/v1
# Anthropic (opt.):  https://api.anthropic.com/v1
baseUrl=http://localhost:11434

# ── 4. API Key ────────────────────────────────────────────────────
# OpenAI: sk-...   Anthropic: sk-ant-...   Ollama: leave blank
apiKey=

# ── 5. Data Platform Mode ─────────────────────────────────────────
# Options: greenplum | openmetadata | both
# Auto-detected from MCP URLs if this field is absent.
activeMode=greenplum

# ── 6. Greenplum MCP Server ───────────────────────────────────────
# Required when activeMode = greenplum or both
mcpUrl=http://your-greenplum-mcp-server:80/mcp
mcpAuth=Basic <base64-encoded-credentials>

# ── 7. OpenMetadata MCP Server ────────────────────────────────────
# Required when activeMode = openmetadata or both
# omMcpAuth: paste the raw JWT — "Bearer " is prepended automatically.
#   Bearer <jwt-token>    (explicit)
#   <raw-jwt-token>       (auto-normalized to Bearer)
#   Basic <base64>        (Basic auth)
omMcpUrl=http://your-openmetadata-server:8080/mcp
omMcpAuth=Bearer <jwt-token>

# ── 8. Custom System Prompt ───────────────────────────────────────
# Pre-training instructions appended to every conversation.
systemPrompt=
```

> **Backward compatibility:** Files without `activeMode` are auto-detected — if both `mcpUrl` and `omMcpUrl` are present the mode is set to `both`; if only `omMcpUrl` is present the mode is set to `openmetadata`; otherwise `greenplum`.

### Data directory layout

All data is stored **in the application directory** (same folder as `start.sh`) by default:

```
greenplum-ai-agent/
├── greenplum-agent.log          # Spring Boot application log
├── stdout.log                   # Console output from daemon
├── agent.pid                    # Running PID (runtime only)
├── global-prompt.txt            # Admin pre-training prompt (if set)
└── users/
    └── {username}/
        ├── config.json          # PIN hash, provider, theme, system prompt, activeMode
        ├── sessions.json        # Sessions, messages, suggestion history
        ├── favourites.json      # Saved favourite prompts
        └── memory/
            └── {sessionId}.json # Per-session AI context window
```

> **Security:** `users/` is in `.gitignore`. It contains PIN hashes and API keys — never commit this directory.

Override the data directory:
```bash
export AGENT_DATA_DIR=/your/custom/path
./start.sh
```

### Server-side persistence

| Data | Stored in | When loaded |
| :--- | :--- | :--- |
| PIN hash | `users/{id}/config.json` | Login verification |
| Provider, model, MCP URLs, API key, mode | `users/{id}/config.json` | After login |
| Theme preference | `users/{id}/config.json` | After login (applied before first paint) |
| Custom system prompt | `users/{id}/config.json` | Sent with every chat request |
| Sessions, messages, timestamps | `users/{id}/sessions.json` | After login |
| Prompt suggestion history | `users/{id}/sessions.json` | After sessions load |
| Saved favourites | `users/{id}/favourites.json` | On sidebar render |
| AI context window | `users/{id}/memory/*.json` | On every chat request |
| Global admin prompt | `global-prompt.txt` | On every chat request |

---

## Data Platform Modes

### Greenplum mode (default)

Connects only to the Greenplum MCP server. The agent uses `GreenplumAgent` with SQL-focused tools (`executeQuery`, `checkTableBloat`, `getClusterStatus`).

### OpenMetadata mode

Connects only to the OpenMetadata MCP server. The agent uses `OpenMetadataAgent` with catalog tools:

| Java Tool | MCP Tool Called | Purpose |
| :--- | :--- | :--- |
| `searchAssets` | `search_metadata` | Keyword search across all asset types |
| `getEntityDetails` | `get_entity_details` | Full metadata for any entity |
| `getLineage` | `get_entity_lineage` | Upstream/downstream lineage |
| `listEntities` | `search_metadata` (with entityType) | Overview list by entity type |
| `getDataQualityResults` | `search_metadata` (entityType=testCase) | Data quality test results |
| `rootCauseAnalysis` | `root_cause_analysis` | Upstream failure + downstream impact |

Supported entity types for search/list: `table`, `database`, `databaseService`, `dashboard`, `topic`, `pipeline`, `mlmodel`, `glossary`, `glossaryTerm`, `domain`, `dataProduct`, `testCase`.

**Auth:** The auth header value is normalized automatically. Paste a raw JWT token and `Bearer ` is prepended. Explicitly formatted values (`Bearer …`, `Basic …`) pass through unchanged.

### Both mode

Connects to both MCP servers simultaneously. The agent uses `GreenplumAgent` registered with both `GreenplumMcpTools` and `OpenMetadataMcpTools`, allowing questions that span database execution and catalog metadata in a single conversation.

---

## Button Reference

| Button | Location | What it does |
| :--- | :--- | :--- |
| `🌙 Dark Mode` / `☀️ Light Mode` | Header | Toggle theme; preference saved to server |
| `🗑️ Clear All Data` | Header | Delete all chats and AI memory; credentials kept |
| `🔐 Admin Panel` | Header | Open global prompt editor (admin PIN required) |
| `⚙️ Settings` | Header | Configure LLM provider, MCP servers, mode, system prompt |
| `+ New Chat` | Sidebar | Start a new conversation tab (max 10) |
| `⭐ Favourite` | Below user messages | Save prompt to favourites with a label |
| `⬇ Export PDF` | Below AI responses | Download branded PDF of the AI response |
| `Test Connection` | Settings modal | Verify LLM + each MCP server; color-coded per service |
| `Save Settings` | Settings modal | Persist configuration to server |
| `🔒 Change PIN` | Settings modal (link) | Open dedicated Change PIN modal |
| `Update PIN` | Change PIN modal | Change current PIN (verifies old PIN first) |
| `Create PIN` | Account setup | Finalise new account creation |
| `Unlock →` | PIN entry | Verify PIN and enter the app |
| `Forgot PIN?` | PIN entry | Show hint and option to reset account |
| `Verify & Enter` | Admin Panel | Authenticate with admin PIN |
| `Save Prompt` | Admin Panel | Save global pre-training prompt |
| `Save Favourite` | Favourites modal | Persist a labelled favourite prompt |
| `Reset Account` | Forgot PIN screen | Delete all user data and start fresh |
| `Yes, Reset Account` | Reset confirmation | Confirm full account wipe |

---

## Data Management

| Action | What is deleted | What is kept |
| :--- | :--- | :--- |
| Per-tab 🗑️ | That session's messages and AI memory | All other sessions, credentials, config |
| **🗑️ Clear All Data** | All sessions and all AI memory | PIN, provider config, theme, favourites |
| **Reset Account** | Everything — credentials, config, sessions, favourites | Nothing — must recreate account |

---

## Cloud Foundry Deployment

### Build
```bash
mvn clean package -DskipTests
```

### `manifest.yml`
```yaml
applications:
  - name: greenplum-ai-agent
    memory: 1G
    disk_quota: 2G
    instances: 1
    path: target/greenplum-ai-agent-*.jar
    buildpacks:
      - java_buildpack
    env:
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 17.+ } }'
      ADMIN_PIN: <your-admin-pin>
```

> Do not commit `manifest.yml` with a real `ADMIN_PIN` value.

### Push & tail logs
```bash
cf login -a <api-endpoint>
cf push
cf logs greenplum-ai-agent --recent
```

### Persistent storage (recommended for CF)

CF's filesystem is ephemeral — data is lost on restarts. Bind an NFS volume service:

```yaml
env:
  AGENT_DATA_DIR: /mnt/gp-data
  ADMIN_PIN: <your-admin-pin>
services:
  - greenplum-agent-volume
```

---

## Architecture

```
Browser  (index.html · app.js)
    │
    │  Authentication
    ├── GET  /api/auth/status        → Is any account registered?
    ├── POST /api/auth/setup         → Create account (stores SHA-256 PIN hash)
    ├── POST /api/auth/verify        → Verify PIN server-side
    │
    │  Configuration & Sessions
    ├── GET  /api/settings/load      → Load config (API keys stripped from response)
    ├── POST /api/settings           → Save config + activeMode to users/{id}/config.json
    ├── GET  /api/sessions/load      → Load sessions, messages, history
    ├── POST /api/sessions/save      → Persist sessions (3-second debounce)
    │
    │  Chat  (POST /api/chat)
    │       │
    │       ▼
    │   ChatController
    │       │
    │       ├── mode = greenplum ──► GreenplumAgent (LangChain4j)
    │       │                              │
    │       │                      GreenplumMcpTools
    │       │                              │
    │       │                       Greenplum Database
    │       │
    │       ├── mode = openmetadata ► OpenMetadataAgent (LangChain4j)
    │       │                              │
    │       │                      OpenMetadataMcpTools
    │       │                              │
    │       │                       OpenMetadata Catalog
    │       │
    │       └── mode = both ──────► GreenplumAgent (LangChain4j)
    │                                      │
    │                         ┌────────────┴────────────┐
    │                  GreenplumMcpTools        OpenMetadataMcpTools
    │                         │                         │
    │                  Greenplum Database        OpenMetadata Catalog
    │
    │  Memory & Admin
    ├── POST /api/memory/clear       → Delete session AI memory files
    ├── POST /api/admin/verify       → Verify admin PIN
    ├── POST /api/admin/save         → Write global-prompt.txt
    │
    │  Favourites
    ├── POST /api/favourites/list    → Read users/{id}/favourites.json
    ├── POST /api/favourites/save    → Append favourite to file
    └── POST /api/favourites/delete  → Remove entry from file
```

**Chat request data flow:**

| Step | What happens |
| :--- | :--- |
| 1 | User sends a message |
| 2 | Server reads `global-prompt.txt` (admin pre-training prompt) |
| 3 | Server reads user's `systemPrompt` and `activeMode` from `config.json` |
| 4 | Agent is built (or retrieved from cache) for the selected mode |
| 5 | Combined prompt + message sent to the agent's `chat()` method |
| 6 | LLM decides which MCP tools to call; tools query the data source(s) |
| 7 | Response sanitised (thinking blocks stripped) |
| 8 | Response streamed to the browser |
| 9 | Browser updates session state; saves to server after 3-second debounce |

**Agent caching:** Each user's agent is rebuilt only when their configuration changes (provider, model, API key, MCP URLs, or mode). A SHA-256-style config hash detects changes; if unchanged the same agent instance (with its in-memory session state) is reused.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| :--- | :--- | :--- |
| Sign In screen on every new browser | Expected — server-side auth | Enter username + PIN |
| "Configuration Required" on first chat | No credentials saved yet | ⚙️ Settings → fill in → Save Settings |
| Settings not loading in incognito | Agent not running | `cat agent.pid` and `tail -f stdout.log` |
| Status dot shows Disconnected | MCP or LLM unreachable | Settings → Test Connection |
| PDF body is blank or text invisible | Browser cached old JS | Hard-refresh: Cmd/Ctrl + Shift + R |
| "Request Timed Out" | Model too slow | Switch to a lighter model |
| Data lost after CF push | No persistent volume | Set `AGENT_DATA_DIR` to an NFS mount path |
| Username rejected at sign-up | Contains `@`, `.`, or spaces | Use letters, numbers, `-`, `_` only |
| Port 8080 already in use | Old instance not stopped | `./stop.sh` or `lsof -ti :8080 \| xargs kill -9` |
| JAR not found after `start.sh` | Maven build failed | Check Maven output for compile errors |
| OM MCP: 401 Unauthorized | Wrong or missing auth token | Paste JWT token in Auth Header field; `Bearer ` is added automatically |
| OM MCP: "Unknown tool" error | Outdated tool name | Restart app — tools are now mapped to official OpenMetadata MCP names |
| OM MCP: "Accept header" error | Missing Accept header | Fixed in current version — rebuild and restart |
| OM MCP returns no results | Asset not indexed yet | Try different search terms or check OpenMetadata ingestion |
| Mode dropdown doesn't show OM fields | Browser cached old JS | Hard-refresh: Cmd/Ctrl + Shift + R |
| Config file loads wrong mode | Old file without `activeMode` | Mode is auto-detected from MCP URLs present in the file |

### Log locations
```bash
tail -f greenplum-agent.log    # Spring Boot application log (MCP tool calls logged here)
tail -f stdout.log             # Console output from the daemon
```

### OpenMetadata MCP log markers

| Log prefix | Meaning |
| :--- | :--- |
| `[OM-MCP] testConnection →` | Auth + URL being used for the connectivity test |
| `[OM-MCP] Available tools:` | Lists all tool names discovered from the server on connect |
| `OM-MCP TOOL OUTBOUND` | Tool name + arguments sent to OpenMetadata |
| `OM-MCP TOOL INBOUND` | Raw response received from OpenMetadata |
| `[OM-MCP] initialize failed →` | Auth error during handshake — check token format |
