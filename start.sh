#!/bin/bash
# Resolve data directory: AGENT_DATA_DIR env var or ~/.greenplum-agent
DATA_DIR="${AGENT_DATA_DIR:-$HOME/.greenplum-agent}"
mkdir -p "$DATA_DIR"

LOG_FILE="$DATA_DIR/stdout.log"
PID_FILE="$DATA_DIR/agent.pid"

# Look for JAR in project root first, then target/ (post-build location)
JAR=$(ls -t greenplum-ai-agent-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    JAR=$(ls -t target/greenplum-ai-agent-*.jar 2>/dev/null | head -1)
fi
if [ -z "$JAR" ]; then
    echo "ERROR: No greenplum-ai-agent-*.jar found in current directory or target/." >&2
    exit 1
fi

echo "Starting Greenplum AI Agent ($JAR) in daemon mode..."
echo "Log: $LOG_FILE"

nohup java -jar "$JAR" > "$LOG_FILE" 2>&1 &

AGENT_PID=$!
echo "$AGENT_PID" > "$PID_FILE"
echo "Agent started with PID $AGENT_PID  (PID file: $PID_FILE)"
