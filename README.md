# Greenplum Database AI Agent

An intelligent, secure, and tool-driven AI agent designed to interact with Greenplum database clusters using the Model Context Protocol (MCP) and dynamic, multi-provider Large Language Models (LLMs).

> [!WARNING]
> **CRITICAL NOTICE:** This project is strictly a Proof of Concept (PoC) and intended solely for experimental purposes. Do **NOT** deploy or run this application in a production environment. Use only in designated, non-production test environments.

---

## 👋 Welcome to Your Greenplum Database AI Assistant!

I am a specialized, read-only Greenplum Database AI assistant designed to help you analyze, diagnose, and optimize your Greenplum cluster. I operate strictly via Greenplum’s Model Context Protocol (MCP) tools to execute precise operations and provide actionable data insights.

### 📸 User Interface & Demo Gallery

Behold the conversational interface in action across various diagnostic scenarios:

| 🟦 Interface Dashboard | 🟦 Tool Execution Output |
| :---: | :---: |
| ![Dashboard](img/img1.jpeg) <br> *Figure 1: Main Application Hub* | ![Execution](img/img2.jpeg) <br> *Figure 2: MCP Tool Invocation* |
| **🟦 Cluster Performance Logs** | **🟦 Query Results & Tables** |
| ![Performance](img/img3.jpeg) <br> *Figure 3: System Optimization Insights* | ![Results](img/img4.jpeg) <br> *Figure 4: Secure Data Inspection View* |
| ![Connectivity](img/img5.jpeg) <br> *Figure 5: Model and MCP server connectivity View*

---

## 🔧 Core Capabilities

| Capability | Tool Used | System Purpose |
| :--- | :--- | :--- |
| **Bloat Analysis** | `checkTableBloat` | Identifies oversized tables requiring `VACUUM` operations. |
| **Cluster Health Check** | `getClusterStatus` | Verifies active segment status, mirroring, and replication health. |
| **Read-Only Queries** | `executeQuery` | Executes optimized `SELECT` statements without modifying data. |

---

## ⚠️ Guardrails & Execution Rules

* **Strictly Read-Only:** The agent is hard-blocked from executing `INSERT`, `UPDATE`, `DELETE`, or structural schema modifications.
* **Deterministic Logic:** Avoids conversational hallucinations by grounding all database diagnostics directly in real-time MCP tool outputs.
* **Schema-Aware Context:** Employs `introspect_database` automatically whenever table or column mappings are ambiguous.
* **Non-Production Scope:** Designed for local evaluation, safe sandboxes, and development testing loops only.

---

## 💡 Example Prompts

Interact with the assistant using natural language commands, which map directly to secure database operations:

* 🗣️ *"Check bloat in the 'sales' table"* ➔ Triggers the `checkTableBloat` tool.
* 🗣️ *"Show cluster status"* ➔ Triggers the `getClusterStatus` tool.
* 🗣️ *"List all users in the 'public' schema"* ➔ Safety-checks and executes an optimized `SELECT` query.

Let me know your Greenplum task—I’ll provide exact SQL, diagnostics, and clear results! 🐘

---

## 📌 Prerequisites

Ensure your host environment meets the following specifications before launching the application:

* **Greenplum MCP Server:** A deployed and active MCP server instance reachable via network IP.
* **LLM Engine:** A localized runner (e.g., Ollama running `qwen3:30b`) or cloud provider credentials (OpenAI/Anthropic).
* **Java Runtime:** Java Development Kit (JDK) 17 or higher.
* **Build Automation (Optional):** Apache Maven 4.0.0+ (only required if building from source).

---

## 🚀 Setup & Execution Guide

### 1. Clone the Source
Pull down the project repository and navigate into the root directory:
```bash
git clone [https://github.com/sendjainabhi/greenplum-ai-agent.git](https://github.com/sendjainabhi/greenplum-ai-agent.git)
cd greenplum-ai-agent
```

### 2. Initialize Local Dependencies (Optional)
If utilizing a local deployment via Ollama instead of a cloud provider, start your model orchestration engine:
```bash
ollama serve
```

### 3. Launch the Application
You do **not** need to hardcode API keys into configuration files. You can run the application using the pre-compiled executable or build it directly from the source code.

#### Option A: Quick Start (Pre-Compiled Executable)
A compiled `.jar` file and startup scripts are already included in the root directory of this repository for your convenience.

**For Windows Users:**
Simply double-click the `start.bat` file in the project folder, or run it via the command prompt:
```cmd
start.bat
```

**For Mac / Linux Users:**
Open your terminal in the project folder, make the script executable (only needed once), and run it:
```bash
chmod +x start.sh
./start.sh
```

#### Option B: Run from Source (Development Mode)
If you prefer to compile and run the source code directly using Maven, run the following command in your terminal:
```bash
mvn clean spring-boot:run
```

---

## ⚙️ Administration & Configuration Workflow

The application employs a streamlined security workflow. Anyone with access to the UI can use the agent, but only administrators can alter the global AI and MCP network routing configurations.

### Standard Usage
1. Open your web browser and navigate to `http://localhost:8080`.
2. Begin chatting immediately!

### Admin Configuration
To change the AI provider, model, or database connection details:

1. Click the **⚙️ Settings** button in the top right corner.
2. Provide the administrator credentials when prompted (Default: `admin` / `admin`).
3. You have two options to configure the system:
   * **Option A: Upload Config File:** Click **"📤 Upload Config File"** to automatically populate the fields using a formatted `.properties` or `.txt` template.
   * **Option B: Manual Entry:**
     * **AI Provider Type:** Select OpenAI Compatible, Ollama, or Anthropic.
     * **Endpoint / Base URL:** The routing URL for your chosen provider.
     * **Authentication / API Key:** Your secure token (if applicable).
     * **Model Name:** The exact model string (e.g., `qwen2.5:32b`, `gpt-4o`).
     * **MCP Server URL & Auth Header:** Your Greenplum MCP routing details.
4. Click **Test Connection** to verify your setup.
5. Click **Save Configuration** to apply the changes globally across the server.

*(Note: Configurations are saved securely to a local `.properties` file on the backend server, ensuring stateless and secure frontend operations without exposing credentials in the browser).*
---

## 📄 Monitoring & Troubleshooting

### Log Inspection
All query generations, tool invocations, and runtime execution graphs are piped to standard out and file appenders.

* If running via the pre-compiled executable or startup scripts, an application log file (`greenplum-agent.log`) will automatically be generated in your current execution directory.
* If running via IDE/Maven, navigate to the **logs directory** generated automatically within the project root workspace.
* Monitor live transactional queries, trace network requests, and review query optimization paths inside the output log streams.