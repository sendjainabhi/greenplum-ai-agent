# Greenplum AI Agent

An intelligent, secure, and tool-driven AI agent designed to interact with Greenplum database clusters using the Model Context Protocol (MCP) and dynamic, multi-provider Large Language Models (LLMs).

> [!WARNING]
> **CRITICAL NOTICE:** This project is strictly a Proof of Concept (PoC) intended solely for experimental purposes. Do **NOT** deploy or run this application in a production environment. Use only in designated, non-production test environments.

---

## 👋 Overview

A specialised, read-only Greenplum Database AI assistant that helps you analyse, diagnose, and optimise your Greenplum cluster. It operates via Greenplum's Model Context Protocol (MCP) tools to execute precise operations and provide actionable data insights — all through a polished chat interface.

### 📸 UI Gallery

| Interface Dashboard | MCP Tool Execution |
| :---: | :---: |
| ![Dashboard](img/img1.jpeg) | ![Execution](img/img2.jpeg) |
| **Cluster Performance** | **Query Results & Tables** |
| ![Performance](img/img3.jpeg) | ![Results](img/img4.jpeg) |
| ![Connectivity](img/img5.jpeg) | |

---

## ✨ Features

### 🎨 Mint & Forest Theme — Dark Mode by Default
- Clean **Mint & Forest** colour palette with forest green (`#2d6a4f`) as the primary brand colour.
- **Dark mode is the default** on every new session; users can switch to light mode anytime.
- Theme preference is saved server-side and restored on every new browser, incognito window, or device — no more re-selecting your preferred theme.
- 3D raised button and panel design with layered shadows throughout the UI.
- Greenplum SVG logo in the header bar for instant brand recognition.

### 🔒 PIN Authentication & Secure Login
- Every user creates a **username + PIN** on first visit — no email, no password manager needed.
  - Usernames: letters, numbers, hyphens and underscores only (`john` or `john_doe`, no `@` or `.`).
- **PIN is always verified server-side** — the browser cache is never used as the authentication authority.
- Opening a new browser, tab, or incognito window shows the **Sign In** screen (username + PIN) — the server determines who has an account, not the browser.
- On the same browser, subsequent tabs and windows boot directly without re-entering the PIN.
- PIN hints are supported; hints are restored automatically after signing in on a new browser.
- Users can change their PIN at any time from **⚙️ Settings**.

### 💬 Multi-Session Chat (up to 10 conversations)
- Run up to **10 independent conversation tabs** simultaneously.
- Each session has its own AI memory, title, and history.
- Rename any tab with ✏️ or delete an individual conversation with 🗑️.
- Sessions, history, and chat data persist across browser refreshes and server restarts — loaded from server files, not browser cache.

### 🗄️ Server-Side Persistence (Everything from the Server)
All user data is stored on the server filesystem and loaded fresh on every new session:

| Data | Storage | Notes |
| :--- | :--- | :--- |
| PIN hash | `users/{userId}/config.json` | SHA-256, never stored in plain text |
| AI provider settings | `users/{userId}/config.json` | MCP URL, model, API key |
| Theme preference | `users/{userId}/config.json` | Restored on every new browser |
| Chat sessions | `users/{userId}/sessions.json` | All tabs, titles, timestamps |
| Chat messages | `users/{userId}/sessions.json` | Full message history per session |
| Saved favourites | `users/{userId}/favourites.json` | Reusable prompts |
| AI memory (context) | `users/{userId}/memory/*.json` | Per-session LLM context window |
| Global admin prompt | `global-prompt.txt` | Applied to all users' requests |

### 🧠 Persistent AI Memory
- AI context is saved per-user, per-session as JSON files on the server.
- Memory window of **30 messages** per session, retained for **90 days**.
- Clearing chat history does **not** delete credentials or configuration.

### 📊 Rich Response Formatting
- Markdown tables, syntax-highlighted SQL/code blocks (Highlight.js), and inline charts (Chart.js).
- **⬇ Export PDF** button on every AI response — exports with the Greenplum logo, forest-green branding, full table and code block content. Works correctly in both light and dark mode.
- Copy button on every code block.

### 🔌 Multi-Provider LLM Support

| Provider | Notes |
| :--- | :--- |
| **Ollama** | Local models — `qwen3:30b`, `llama3`, etc. |
| **OpenAI Compatible** | ChatGPT, vLLM, LMStudio, any OpenAI-spec endpoint |
| **Anthropic** | Claude models via Anthropic API |

### ⚡ Smart UX
- Debounced autocomplete based on your past session history.
- Auto-connects on page load using saved credentials (loaded from server, not browser cache).
- Cancel in-flight requests at any time.
- ⭐ **Favourite** any prompt for quick reuse — click `⭐ Favourite` below any message you sent.

### 🔐 Admin Panel — Global Pre-Training Prompt
Administrators can set a **global pre-training prompt** appended silently to every chat request for every user.

- Accessed via **🔐 Admin Panel** in the header using a server-configured PIN.
- Takes effect immediately on the next request after saving — no restart needed.
- Adds to each user's personal system prompt without overriding it.
- Useful for org-wide policies: language, scope, tone, data classification rules.

---

## 🔧 Core Capabilities

| Capability | Tool Used | Purpose |
| :--- | :--- | :--- |
| **Bloat Analysis** | `checkTableBloat` | Identifies oversized tables requiring `VACUUM` |
| **Cluster Health Check** | `getClusterStatus` | Verifies segment status, mirroring, replication |
| **Read-Only Queries** | `executeQuery` | Executes optimised `SELECT` statements |

---

## ⚠️ Guardrails & Execution Rules

- **Strictly Read-Only:** Hard-blocked from `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`.
- **Schema-Aware:** Always queries `information_schema.columns` before accessing any table.
- **Thinking Block Stripping:** Internal `<think>` blocks from models like Qwen3 and DeepSeek-R1 are stripped before display.
- **Non-Production Scope:** Designed for evaluation, safe sandboxes, and development testing only.

---

## 💡 Example Prompts

- 🗣️ *"Check bloat in the 'sales' table"* — triggers `checkTableBloat`
- 🗣️ *"Show cluster status"* — triggers `getClusterStatus`
- 🗣️ *"List all tables in the finance schema"* — safe `SELECT` via `executeQuery`
- 🗣️ *"What indexes exist on the orders table?"* — schema introspection query

---

## 📌 Prerequisites

| Requirement | Details |
| :--- | :--- |
| **Java** | JDK 17 or higher |
| **Maven** | 3.8+ (bundled `mvnw` wrapper included) |
| **Greenplum MCP Server** | Deployed and reachable via network |
| **LLM Engine** | Ollama (local) or cloud API credentials (OpenAI / Anthropic) |

---

## 🚀 Local Deployment

### 1. Clone the Repository
```bash
git clone https://github.com/sendjainabhi/greenplum-ai-agent.git
cd greenplum-ai-agent
```

### 2. Start Ollama (if using a local model)
```bash
ollama serve
```

### 3. Build & Run

**Option A — `start.sh` (recommended — builds, replaces JAR, starts the agent)**

```bash
chmod +x start.sh stop.sh
./start.sh
```

`start.sh` automatically:
1. Runs `mvn clean package -DskipTests` to build a fresh JAR
2. Replaces the old JAR in the project root with the new build from `target/`
3. Stops any previously running instance gracefully
4. Starts the agent as a background daemon

To stop the agent:
```bash
./stop.sh
```

**Option B — Run a pre-built JAR directly**
```bash
java -jar greenplum-ai-agent-*.jar
```

**Option C — Maven Spring Boot (development mode)**
```bash
mvn spring-boot:run
```

### 4. Open the UI
Navigate to `http://localhost:8080` in your browser.

---

### Data & Log Location (Local)

By default, all data is stored **in the application's own directory** (the same folder as the JAR and `start.sh`):

```
greenplum-ai-agent/
├── greenplum-agent.log          # Application log
├── global-prompt.txt            # Admin global pre-training prompt (if set)
├── stdout.log                   # Console output log
└── users/
    └── {username}/
        ├── config.json          # Settings, PIN hash, theme preference, API config
        ├── sessions.json        # Chat sessions, messages, history
        ├── favourites.json      # User's saved favourite prompts
        └── memory/
            └── {sessionId}.json # Per-session AI memory (LLM context window)
```

> **Note:** The `users/` directory contains PIN hashes and API keys — it is excluded from version control via `.gitignore` and should never be committed or shared.

To use a custom data directory:
```bash
export AGENT_DATA_DIR=/your/custom/path
./start.sh
```

---

## ☁️ Cloud Foundry Deployment

### 1. Build the JAR
```bash
mvn clean package -DskipTests
```

### 2. Configure `manifest.yml`
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

> **Security:** Do not commit `manifest.yml` with an actual `ADMIN_PIN` value to version control.

### 3. Push to Cloud Foundry
```bash
cf login -a <api-endpoint>
cf push
```

### 4. View Logs
```bash
cf logs greenplum-ai-agent --recent
cf logs greenplum-ai-agent             # live tail
```

### Persistent Storage on CF (Recommended)

CF uses an ephemeral filesystem by default — user configs, chat memory, and the global prompt are lost on restarts. To persist data:

1. Provision an NFS Volume Service through your CF operator.
2. Bind it and set `AGENT_DATA_DIR`:

```yaml
env:
  AGENT_DATA_DIR: /mnt/gp-data
  ADMIN_PIN: <your-admin-pin>
services:
  - greenplum-agent-volume
```

Without a persistent volume, users re-enter credentials after each `cf push` via the **Sign In** screen.

---

## ⚙️ First-Time Setup

### Create a New Account (First Visit)
1. Open the app — a **Sign In** screen appears.
2. Click **"Don't have an account? Create one"** (text link below the Sign In button).
3. Choose a **username** — letters, numbers, hyphens and underscores only (e.g. `john` or `john_doe`). No email addresses.
4. Set a **PIN** (minimum 4 characters) and an optional memory hint.
5. Click **Create PIN** — your account is created and the app loads immediately.

### Sign In (Returning User — New Browser or Incognito)
1. Open the app — the **Sign In** screen appears (server determines who has an account).
2. Enter your username and PIN.
3. Click **Sign In** — the server verifies your PIN and restores your full session: settings, chat history, theme, and favourites.

> On the **same browser**, subsequent tabs and windows unlock with PIN only — no username required.

### Configure AI Provider
1. Click **⚙️ Settings** in the header.
2. Upload a `.properties` credential file or fill in the fields manually:
   - **AI Provider:** Ollama / OpenAI Compatible / Anthropic
   - **Endpoint / Base URL**
   - **API Key** (if required)
   - **Model Name** (e.g. `qwen3:30b`, `gpt-4o`, `claude-sonnet-4-6`)
   - **MCP Server URL & Auth Header**
3. Click **Test Connection** to verify connectivity, then **Save Settings**.

---

## 🖱️ Button Reference

| Button | Location | Action |
| :--- | :--- | :--- |
| `☀️ Light Mode` / `🌙 Dark Mode` | Header | Toggle theme (saved server-side) |
| `🗑️ Clear All Data` | Header | Delete all chats and AI memory (keeps credentials) |
| `🔐 Admin Panel` | Header | Open global pre-training prompt editor (PIN protected) |
| `⚙️ Settings` | Header | Configure AI provider, MCP, PIN |
| `+ New Chat` | Sidebar | Start a new conversation tab |
| `⭐ Favourite` | Below your messages | Save prompt to favourites list |
| `⬇ Export PDF` | Below AI responses | Export response as branded PDF |
| `Test Connection` | Settings modal | Verify MCP + LLM connectivity |
| `Save Settings` | Settings modal | Persist configuration to server |
| `Create PIN` | Account setup | Finalise account creation |
| `Unlock →` | PIN entry | Verify PIN and enter the app |
| `Verify & Enter` | Admin Panel | Authenticate with admin PIN |
| `Reset Account` | Forgot PIN screen | Delete all data and start fresh |

---

## 🗑️ Managing Chat History

| Action | What it does |
| :--- | :--- |
| 🗑️ (per tab) | Deletes that single conversation and its AI memory |
| **🗑️ Clear All Data** (header) | Deletes all conversations and all AI memory |
| Both of the above | **Credentials, PIN, theme, and configuration are preserved** |
| **Reset Account** (Forgot PIN → Reset) | Deletes everything including credentials — requires setting up a new account |

---

## 📄 Monitoring & Troubleshooting

### Local Logs
```bash
# Application log
tail -f greenplum-agent.log

# Console output
tail -f stdout.log
```

### CF Logs
```bash
cf logs greenplum-ai-agent --recent
cf logs greenplum-ai-agent
```

### Common Issues

| Symptom | Likely Cause | Fix |
| :--- | :--- | :--- |
| Sign In appears on every new browser | Expected — server-side auth | Enter username + PIN to sign in |
| "Configuration Required" on first chat | No credentials saved yet | Open ⚙️ Settings and save your config |
| Settings not loading in incognito | Should not happen — settings load from server | Check server is running and reachable |
| Status shows Disconnected | Model or MCP unreachable | Click Test Connection in Settings |
| PDF exports blank or invisible content | Old browser cache | Hard-refresh (Cmd/Ctrl + Shift + R) |
| "Request Timed Out" | Model taking too long | Try a lighter model or simpler query |
| Data lost after CF push | No persistent volume | Use `AGENT_DATA_DIR` with a mounted NFS volume |

---

## 🏗️ Architecture

```
Browser (index.html + app.js + style.css)
    │
    ├── GET  /api/auth/status          → Check if any user account exists on server
    ├── POST /api/auth/setup           → Create account (stores SHA-256 PIN hash)
    ├── POST /api/auth/verify          → Verify PIN server-side
    ├── GET  /api/settings/load        → Load config from server (no PIN hash returned)
    ├── POST /api/settings             → Save config + theme to server
    ├── GET  /api/sessions/load        → Load sessions, messages, history from server
    ├── POST /api/sessions/save        → Persist sessions, messages, history to server
    │
    ├── POST /api/chat                 → ChatController → GreenplumAgent (LangChain4j)
    │                                         │
    │                                   GreenplumMcpTools
    │                                         │
    │                                   Greenplum MCP Server
    │                                         │
    │                                   Greenplum Database
    │
    ├── POST /api/memory/clear         → Delete per-session AI memory files
    ├── POST /api/admin/verify         → Verify admin PIN
    ├── POST /api/admin/save           → Save global-prompt.txt
    ├── POST /api/favourites/list      → List user favourites
    ├── POST /api/favourites/save      → Save a favourite prompt
    └── POST /api/favourites/delete    → Delete a favourite prompt
```

**Data flow for each chat request:**
1. User sends a message
2. Server loads `global-prompt.txt` from disk (if set by admin)
3. Server loads user's `systemPrompt` from their `config.json`
4. Combined prompt → `GreenplumAgent.chat()` → LLM → optional MCP tool calls → response
5. Response sanitised (thinking blocks stripped, invalid characters removed) → returned to browser
6. Browser saves updated session data back to server via `POST /api/sessions/save` (3-second debounce)
