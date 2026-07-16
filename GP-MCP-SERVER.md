# Greenplum MCP Server — Tanzu Platform (Cloud Foundry) Deployment Guide

> This server is a prerequisite for the Greenplum AI Analytics Agent. Deploy it first, then bind it to the main app via the `gp-mcp-greenplum` user-provided service.

---

## Overview

The Greenplum MCP Server exposes Greenplum database capabilities as Model Context Protocol (MCP) tools for AI assistants. It runs as a Cloud Foundry app using the Streamable HTTP transport.

| | |
|---|---|
| **Endpoint** | `https://<your-mcp-app-route>/mcp` |
| **Auth** | HTTP Basic Auth |
| **Database** | `<your-gp-db-host>:5432 / <your-db-name>` |

---

## Files

| File | Purpose |
|---|---|
| `gp-mcp-server-linux-x64` | Pre-built statically linked Linux x64 binary |
| `config.yaml` | MCP server configuration — DB connection, auth, logging |
| `policy.yaml` | Access policy — read-only enforcement, denied SQL operations |
| `manifest.yml` | Cloud Foundry push manifest |
| `start.sh` | Startup wrapper — patches CF's dynamic `$PORT` into config |

---

## Prerequisites

- CF CLI installed (`brew install cf-cli@8` on Mac)
- Logged in to Tanzu Platform:

```bash
cf login -a https://api.<your-cf-domain>
# org: <your-org>  /  space: <your-space>
```

- Outbound network access from CF to `<your-gp-db-host>:5432` must be open (CF Application Security Group)

---

## Configuration

### config.yaml

```yaml
mcp:
  host: "0.0.0.0"
  port: 8080                          # Patched at runtime by start.sh with CF's $PORT
  endpoint: "/mcp"
  allow-non-local-without-tls: true   # Required for 0.0.0.0 binding without TLS

service:
  gpdb:
    host: "<your-gp-db-host>"
    port: 5432
    database: "<your-db-name>"
    username: "<your-db-user>"
    password: "<your-db-password>"
    max_rows: 1000
    skip_connection_check: true       # Skip DB check at startup

auth:
  enabled: true
  type: "basic"                       # HTTP Basic Auth
  rate_limit_failures: 10
```

> `skip_connection_check: true` allows the server to start even if the database is temporarily unreachable. Remove this flag only if you want a hard startup failure on DB unavailability.

### policy.yaml

All users are restricted to **read-only** access. Write operations are blocked at the policy layer regardless of the database user's privileges:

```yaml
gpdb:
  all:
    readonly: true
    denied:
      - INSERT
      - UPDATE
      - DELETE
      - DROP
      - TRUNCATE
      - CREATE
      - ALTER
      - GRANT
      - REVOKE
```

### manifest.yml

```yaml
---
applications:
- name: gp-mcp-server
  memory: 256M
  instances: 1
  buildpacks:
    - binary_buildpack
  command: bash start.sh
  health-check-type: http
  health-check-http-endpoint: /mcp
```

### start.sh

The startup script patches the dynamically assigned CF `$PORT` into `config.yaml` before launching the binary:

```bash
#!/usr/bin/env bash
set -e
sed -i "s/port: 8080/port: ${PORT}/" config.yaml
exec ./gp-mcp-server-linux-x64 \
  --config-file=./config.yaml \
  --policy-file=./policy.yaml \
  streamable-http
```

> CF assigns a random port to `$PORT` at container startup — the binary must bind to exactly that port or the Gorouter will report 502.

---

## Deploy

```bash
cd mcp-server

# Make binaries executable (first time only)
chmod +x gp-mcp-server-linux-x64 start.sh

# Push to Cloud Foundry
cf push
```

CF picks up `manifest.yml` automatically. The deployment:
1. Uploads all files using `binary_buildpack` (no compilation needed)
2. Runs `bash start.sh` which patches `$PORT` into `config.yaml`
3. Starts: `./gp-mcp-server-linux-x64 --config-file=./config.yaml --policy-file=./policy.yaml streamable-http`

---

## Verify Deployment

```bash
# Check app state — look for: state: running, instances: 1/1
cf app gp-mcp-server
```

Expected output:

```
     state     since    cpu    memory       disk
#0   running   ...      0.5%   18M of 256M  51M of 512M
```

---

## Check Logs

```bash
# Tail live logs (best during startup or debugging)
cf logs gp-mcp-server

# View recent logs (startup output, last ~1000 lines)
cf logs gp-mcp-server --recent

# Filter to app output only
cf logs gp-mcp-server --recent | grep "APP/PROC/WEB"

# Check for crashes and restarts
cf events gp-mcp-server
```

Healthy startup output:

```
[APP/PROC/WEB/0] ERR level=info msg="Loaded configuration from: ./config.yaml"
[APP/PROC/WEB/0] OUT db host: <your-gp-db-host>
[APP/PROC/WEB/0] OUT db port: 5432
[APP/PROC/WEB/0] ERR level=info msg="GP MCP Server running on streamable-http"
```

The server also writes a detailed log to `server.log` inside the container:

```bash
cf ssh gp-mcp-server -c "cat /home/vcap/app/server.log"
```

---

## Test the Endpoint

### Basic connectivity

```bash
curl -u <your-db-user>:<your-db-password> \
  https://<your-mcp-app-route>/mcp
```

Expected: `200 OK` with an MCP JSON response body.

### MCP Initialize (full handshake)

```bash
curl -u <your-db-user>:<your-db-password> \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": { "name": "test", "version": "1.0" }
    }
  }' \
  https://<your-mcp-app-route>/mcp
```

### List available tools

```bash
curl -u <your-db-user>:<your-db-password> \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }' \
  https://<your-mcp-app-route>/mcp
```

### Test auth rejection

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -u wronguser:wrongpass \
  https://<your-mcp-app-route>/mcp
# Expected: 401
```

---

## Available MCP Tools

The server exposes 35 built-in Greenplum tools across six categories:

| Category | Tools |
|---|---|
| **Discovery** | `list_databases`, `list_schemas`, `list_tables`, `list_objects`, `list_users` |
| **Inspection** | `database_info`, `cluster_info`, `cluster_status`, `describe_tables`, `get_object_details` |
| **Query** | `execute_query`, `explain_query`, `diagnose_query` |
| **Health** | `check_table_bloat`, `check_stats_freshness`, `check_long_running_queries`, `check_table_skew`, `check_index_bloat` |
| **Performance** | `check_resource_group_activity`, `check_query_spill_usage`, `check_disk_space` |
| **Analysis** | `analyze_schemas_size`, `find_largest_databases`, `find_largest_schemas`, `find_largest_tables`, `find_largest_indexes`, `find_largest_partitions`, `analyze_view_dependencies`, `introspect_database` |
| **Toolkit** | `query_gp_toolkit`, `get_table_madlib_analytics` |

---

## Bind to the AI Agent App

After the MCP server is running, create a user-provided service so the AI agent can discover it via `VCAP_SERVICES`:

```bash
ENCODED_AUTH=$(echo -n "<your-db-user>:<your-db-password>" | base64)

cf create-user-provided-service gp-mcp-greenplum \
  -p "{
    \"type\": \"greenplum\",
    \"url\":  \"https://<your-mcp-app-route>/mcp\",
    \"auth\": \"Basic ${ENCODED_AUTH}\"
  }"
```

Then add `gp-mcp-greenplum` to the `services:` list in the AI agent's `manifest.yml` and repush.

---

## Connect from Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "greenplum": {
      "type": "streamable-http",
      "url": "https://<your-mcp-app-route>/mcp",
      "headers": {
        "Authorization": "Basic <base64(user:password)>"
      }
    }
  }
}
```

> Generate the `Authorization` header value:
> ```bash
> echo -n "username:password" | base64
> ```

---

## Re-deploy after Config Changes

Edit `config.yaml` or `policy.yaml` locally, then:

```bash
cf push
```

Config and policy files are hot-reloaded every 30 seconds automatically. To trigger an immediate reload without a full redeploy:

```bash
cf ssh gp-mcp-server -c "kill -HUP \$(pgrep gp-mcp-server)"
```

### Scale or restart

```bash
cf restart gp-mcp-server          # restart (keeps instance count)
cf scale gp-mcp-server -i 2       # scale to 2 instances
cf scale gp-mcp-server -m 512M    # increase memory
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `502 Bad Gateway` on startup | App hasn't started listening yet | Wait 10–15 s then retry; check `cf logs` |
| `502 Bad Gateway` (persistent) | Server stuck on DB connection | Verify port 5432 is open from CF to the DB host |
| `401 Unauthorized` | Wrong credentials in request | Use the credentials set in `config.yaml` auth section |
| App crashes at start | Wrong start command or missing `streamable-http` subcommand | Verify `start.sh` passes `streamable-http` and `--config-file` flag |
| Policy not loaded | Relative path not resolving | Pass `--policy-file=./policy.yaml` explicitly (already in `start.sh`) |
| PORT mismatch / 502 | `start.sh` sed not replacing port | Check `start.sh` runs before binary; verify `$PORT` is set |

### Check DB connectivity from inside the container

```bash
cf ssh gp-mcp-server -c \
  "timeout 5 bash -c 'echo > /dev/tcp/<your-gp-db-host>/5432' \
   && echo PORT_OPEN || echo PORT_BLOCKED"
```

### Check Application Security Group

If `PORT_BLOCKED`, the CF ASG may be restricting egress. Ask a platform operator to add an egress rule:

```json
[{ "protocol": "tcp", "destination": "<your-gp-db-host>", "ports": "5432" }]
```

```bash
cf bind-security-group <asg-name> <your-org> <your-space> --lifecycle running
cf restart gp-mcp-server
```
