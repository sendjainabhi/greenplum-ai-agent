# Greenplum Database AI Agent

An intelligent, secure, and tool-driven AI agent designed to interact with Greenplum database clusters using the Model Context Protocol (MCP) and dynamic, multi-provider Large Language Models (LLMs).

> [!WARNING]
> **CRITICAL NOTICE:** This project is strictly a Proof of Concept (PoC) and intended solely for experimental purposes. Do **NOT** deploy or run this application in a production environment. Use only in designated, non-production test environments.

---

## 👋 Welcome to Your Greenplum Database AI Assistant!

A specialized, read-only Greenplum Database AI assistant designed to help you analyze, diagnose, and optimize your Greenplum cluster. It operates strictly via Greenplum's Model Context Protocol (MCP) tools to execute precise operations and provide actionable data insights.

### 📸 User Interface & Demo Gallery

| 🟦 Interface Dashboard | 🟦 Tool Execution Output |
| :---: | :---: |
| ![Dashboard](img/img1.jpeg) <br> *Figure 1: Main Application Hub* | ![Execution](img/img2.jpeg) <br> *Figure 2: MCP Tool Invocation* |
| **🟦 Cluster Performance Logs** | **🟦 Query Results & Tables** |
| ![Performance](img/img3.jpeg) <br> *Figure 3: System Optimization Insights* | ![Results](img/img4.jpeg) <br> *Figure 4: Secure Data Inspection View* |
| ![Connectivity](img/img5.jpeg) <br> *Figure 5: Model and MCP server connectivity View* | |

---

## ✨ Features

### 🔒 PIN Authentication & Account Recovery
- Every user creates a **username + PIN** on first visit — no email, no password manager needed.
- The PIN hash is stored both in the browser and on the server, so even after a browser cache clear, users can recover their account by entering their username and PIN.
- PIN hints are supported for recovery assistance.
- Users can change their PIN at any time from **⚙️ Settings**.

### 💬 Multi-Session Chat
- Run up to **4 independent conversation tabs** simultaneously.
- Each session has its own AI memory, title, and history.
- Rename any tab with ✏️ or delete an individual conversation with 🗑️.
- Sessions persist across browser refreshes and server restarts.

### 🧠 Persistent AI Memory
- AI context is saved per-user, per-session as JSON files on the server.
- Memory window of **30 messages** per session, retained for **90 days**.
- Clearing chat history does **not** delete your credentials or configuration.

### 📊 Rich Response Formatting
- Markdown tables, syntax-highlighted SQL blocks, and inline charts (Chart.js).
- One-click **PDF export** for any AI response.
- Copy button on every code block.

### 🔌 Multi-Provider LLM Support
| Provider | Notes |
| :--- | :--- |
| **Ollama** | Local models — `qwen3:30b`, `llama3`, etc. |
| **OpenAI Compatible** | ChatGPT, vLLM, LMStudio, any OpenAI-spec endpoint |
| **Anthropic** | Claude models via Anthropic API |

### ⚡ Autocomplete & Smart UX
- 50ms-debounced autocomplete based on your past session history.
- Auto-connects on page load using saved credentials.
- Cancel in-flight requests at any time.

### 🔐 Admin — Global Pre-Training Prompt
Administrators can set a **global pre-training prompt** that is silently appended to every chat request for every user — without any code change, config file edit, or server restart.

- Accessed via the **🔐 Admin** button in the header using a server-configured PIN.
- The global prompt is stored on disk and takes effect immediately on the next request after saving.
- It **adds to** each user's personal system prompt — it does not override it.
- Useful for org-wide policies: language restrictions, scope restrictions, tone requirements, data classification rules, etc.
- Leave the global prompt blank to disable it.

---

## 🔧 Core Capabilities

| Capability | Tool Used | Purpose |
| :--- | :--- | :--- |
| **Bloat Analysis** | `checkTableBloat` | Identifies oversized tables requiring `VACUUM` operations |
| **Cluster Health Check** | `getClusterStatus` | Verifies segment status, mirroring, and replication health |
| **Read-Only Queries** | `executeQuery` | Executes optimized `SELECT` statements without modifying data |

---

## ⚠️ Guardrails & Execution Rules

- **Strictly Read-Only:** The agent is hard-blocked from executing `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, or `TRUNCATE`.
- **Schema-Aware:** Always queries `information_schema.columns` before accessing any table to avoid column name guessing.
- **Thinking Block Stripping:** Internal `<think>` reasoning blocks from models like Qwen3 and DeepSeek-R1 are stripped before display.
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
| **Greenplum MCP Server** | Deployed and reachable via network |
| **LLM Engine** | Ollama (local) or cloud API credentials (OpenAI / Anthropic) |
| **Maven** | 4.0.0+ (only if building from source) |

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

### 3. Run the Application

**Option A — Pre-compiled JAR**

Mac / Linux:
```bash
chmod +x start.sh
./start.sh
```

Windows:
```cmd
start.bat
```

**Option B — Build from Source**
```bash
mvn clean package -DskipTests
java -jar target/greenplum-ai-agent-*.jar
```

**Option C — Maven Spring Boot**
```bash
mvn clean spring-boot:run
```

### 4. Open the UI
Navigate to `http://localhost:8080` in your browser.

### Data & Log Location (Local)
By default all data is stored under `~/.greenplum-agent/`:

```
~/.greenplum-agent/
├── greenplum-agent.log          # Application log
├── global-prompt.txt            # Admin global pre-training prompt (if set)
└── users/
    └── {username}/
        ├── config.json          # User credentials, model config, PIN hash
        └── memory/
            └── {sessionId}.json # Per-session AI memory
```

To use a custom data directory:
```bash
export AGENT_DATA_DIR=/your/custom/path
java -jar target/greenplum-ai-agent-*.jar
```

---

## ☁️ Cloud Foundry Deployment

### 1. Build the JAR
```bash
mvn clean package -DskipTests
```

### 2. Create `manifest.yml`
Create a `manifest.yml` in the project root:

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

> **Note:** `ADMIN_PIN` sets the password for the **🔐 Admin** global prompt feature. If omitted, a default PIN is used — contact your deployment administrator for the value. Do not commit this file with the PIN to version control.

### 3. Push to Cloud Foundry
```bash
cf login -a <api-endpoint>
cf push
```

### 4. View Logs
```bash
cf logs greenplum-ai-agent --recent    # past logs
cf logs greenplum-ai-agent             # live tail
```

### Persistent Storage on CF (Optional but Recommended)

By default CF uses an **ephemeral filesystem** — user configs, chat memory, and the global prompt are lost when the container restarts or a new version is deployed. To persist data across deployments:

1. Provision an NFS Volume Service through your CF operator.
2. Bind it to the app and set the mount path via `AGENT_DATA_DIR`:

```yaml
env:
  AGENT_DATA_DIR: /mnt/gp-data
  ADMIN_PIN: <your-admin-pin>
services:
  - greenplum-agent-volume
```

Without a persistent volume, users will need to re-enter their credentials after each `cf push`. The app handles this gracefully — users can recover their session using the **"Already have an account?"** flow on the login screen.

### CF Scaling Note
When running **multiple instances** (`instances: 2+`), each instance has its own filesystem. The global admin prompt and user data are per-instance. For multi-instance deployments, a shared persistent volume (`AGENT_DATA_DIR` pointing to a shared NFS mount) is required to keep all instances in sync.

---

## ⚙️ First-Time Setup (All Environments)

### User Account Setup
1. Open the app — a **Create Your Account** modal appears.
2. Choose a **username** (letters, numbers, `-` or `_`, 3–50 characters).
3. Set a **PIN** (minimum 4 characters) and an optional hint.
4. Click **Set PIN & Continue** — your account is ready.

### Account Recovery (After Cache Clear)
1. On the login screen, click **"Already have an account?"**
2. Enter your username and PIN — the server verifies and restores your session.

### Configure AI Provider
1. Click **⚙️ Settings** in the header.
2. Upload a `.properties` credential file or fill in the fields manually:
   - **AI Provider:** Ollama / OpenAI Compatible / Anthropic
   - **Endpoint / Base URL**
   - **API Key** (if required)
   - **Model Name** (e.g. `qwen3:30b`, `gpt-4o`, `claude-sonnet-4-6`)
   - **MCP Server URL & Auth Header**
3. Click **Test Connection** to verify, then **Save Configuration**.

### Sample Credential File
A sample `.properties` file is available for download directly from the Settings modal. Use it as a template to pre-fill all fields in one click.

---

## 🗑️ Managing Chat History

| Action | What it does |
| :--- | :--- |
| 🗑️ (per tab) | Deletes that single conversation and its AI memory |
| **Delete Chat History & Data** (header) | Deletes all conversations and all AI memory |
| Both of the above | **Credentials and configuration are preserved** — you can chat again immediately |
| **Reset PIN** (forgot PIN flow) | Deletes everything including credentials — full reset |

---

## 📄 Monitoring & Troubleshooting

### Local Logs
```bash
tail -f ~/.greenplum-agent/greenplum-agent.log
```

### CF Logs
```bash
cf logs greenplum-ai-agent --recent
cf logs greenplum-ai-agent             # live
```

### Common Issues

| Symptom | Likely Cause | Fix |
| :--- | :--- | :--- |
| "Configuration Required" on first chat | No credentials saved | Open Settings and save your config |
| "Request Timed Out" | Model taking too long | Try a lighter model or a simpler query |
| Status shows Disconnected | Model/MCP unreachable | Click Test Connection in Settings |
| Data lost after CF push | No persistent volume | Use `AGENT_DATA_DIR` with a mounted volume |

---

## 🏗️ Architecture Overview

```
Browser (index.html + app.js)
    │
    ├── POST /api/chat          → ChatController → GreenplumAgent (LangChain4j)
    │                                                     │
    │                                              GreenplumMcpTools
    │                                                     │
    │                                              Greenplum MCP Server
    │                                                     │
    │                                              Greenplum Database
    │
    ├── POST /api/settings      → ChatController → users/{userId}/config.json
    ├── POST /api/auth/setup    → ChatController → users/{userId}/config.json (PIN hash)
    ├── POST /api/auth/verify   → ChatController → server-side PIN verification
    ├── POST /api/memory/clear  → ChatController → users/{userId}/memory/*.json
    ├── POST /api/admin/verify  → ChatController → in-memory PIN check
    └── POST /api/admin/save    → ChatController → global-prompt.txt
```

**Data flow for each chat request:**
1. User types a question
2. Server loads `global-prompt.txt` from disk (if set by admin)
3. Server loads user's `systemPrompt` from their `config.json`
4. Combined prompt → `GreenplumAgent.chat()` → LLM → optional MCP tool calls → response
5. Response sanitized (thinking blocks stripped, invalid characters removed) → returned to browser
