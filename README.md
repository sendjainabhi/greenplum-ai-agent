# Greenplum Database AI Agent

An intelligent, secure, and tool-driven AI agent designed to interact with Greenplum database clusters using the Model Context Protocol (MCP) and localized Large Language Models (LLMs).

> [!WARNING]
> **CRITICAL NOTICE:** This project is strictly a Proof of Concept (PoC) and intended solely for experimental purposes. Do **NOT** deploy or run this application in a production environment. Use only in designated, non-production test environments.

---

## 👋 Welcome to Your Greenplum Database AI Assistant!

I am a specialized, read-only Greenplum Database AI assistant designed to help you analyze, diagnose, and optimize your Greenplum cluster. I operate strictly via Greenplum’s Model Context Protocol (MCP) tools to execute precise operations and provide actionable data insights.

### 📸 User Interface & Demo Gallary

Behold the conversational interface in action across various diagnostic scenarios:


| 🟦 Interface Dashboard | 🟦 Tool Execution Output |
| :---: | :---: |
| ![Dashboard](img/img1.jpeg) <br> *Figure 1: Main Application Hub* | ![Execution](img/img2.jpeg) <br> *Figure 2: MCP Tool Invocation* |
| **🟦 Cluster Performance Logs** | **🟦 Query Results & Tables** |
| ![Performance](img/img3.jpeg) <br> *Figure 3: System Optimization Insights* | ![Results](img/img4.jpeg) <br> *Figure 4: Secure Data Inspection View* |

---

### 🔧 Core Capabilities



| Capability | Tool Used | System Purpose |
| :--- | :--- | :--- |
| **Bloat Analysis** | `checkTableBloat` | Identifies oversized tables requiring `VACUUM` operations. |
| **Cluster Health Check** | `getClusterStatus` | Verifies active segment status, mirroring, and replication health. |
| **Read-Only Queries** | `executeQuery` | Executes optimized `SELECT` statements without modifying data. |

### ⚠️ Guardrails & Execution Rules

* **Strictly Read-Only:** The agent is hard-blocked from executing `INSERT`, `UPDATE`, `DELETE`, or structural schema modifications.
* **Deterministic Logic:** Avoids conversational hallucinations by grounding all database diagnostics directly in real-time MCP tool outputs.
* **Schema-Aware Context:** Employs `introspect_database` automatically whenever table or column mappings are ambiguous.
* **Non-Production Scope:** Designed for local evaluation, safe sandboxes, and development testing loops only.

### 💡 Example Prompts

Interact with the assistant using natural language commands, which map directly to secure database operations:
* 🗣️ *"Check bloat in the 'sales' table"* ➔ Triggers the `checkTableBloat` tool.
* 🗣️ *"Show cluster status"* ➔ Triggers the `getClusterStatus` tool.
* 🗣️ *"List all users in the 'public' schema"* ➔ Safety-checks and executes an optimized `SELECT` query.

Let me know your Greenplum task—I’ll provide exact SQL, diagnostics, and clear results! 🐘

---

## 📌 Prerequisites

Ensure your host environment meets the following specifications before launching the application:

### Infrastructure & Language Models
* **Greenplum MCP Server:** A deployed and active MCP server instance reachable via network IP.
* **LLM Engine:** A localized runner (e.g., Ollama running `qwen3:30b`) or an external model host accessible from the application environment.

### Software Requirements
* **Java Runtime:** Java Development Kit (JDK) 17 or higher.
* **Build Automation:** Apache Maven 4.0.0+ configured in your local environment.

---

## 🚀 Setup & Execution Guide

### 1. Clone the Source
Pull down the project repository and navigate into the root directory:
```bash
git clone https://github.com/sendjainabhi/greenplum-ai-agent.git
cd greenplum-ai-agent
```

### 2. Initialize the Local Language Model
If utilizing a local deployment via Ollama, start the model orchestration engine:
```bash
ollama serve
```

### 3. Application Configuration
Update your configuration management files (`application.yml` or `application.properties`) to reference your target infrastructure:

#### LLM Profile (Ollama)
```yaml
ollama:
  server:
    url: http://localhost:11434
  model:
    name: qwen3:30b
    timeout-seconds: 360
```

#### Greenplum MCP Connectivity
```yaml
mcp:
  server:
    url: http://<greenplum mcp server host ip>/mcp
    auth-header: Basic <gp mcp server auth base 64 (user:password)>
```

### 4. Run the Engine
Compile dependencies and boot up the Spring Boot framework:
```bash
mvn clean compile  
mvn spring-boot:run
```

---

## 📄 Monitoring & Troubleshooting

### Log Inspection
All query generations, tool invocations, and runtime execution graphs are piped to standard out and file appenders.
* Navigate to the **logs directory** generated automatically within the project root workspace.
* Monitor live transactional queries, trace network requests, and review query optimization paths inside the output log streams.
