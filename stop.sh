#!/bin/bash
DATA_DIR="${AGENT_DATA_DIR:-$HOME/.greenplum-agent}"
PID_FILE="$DATA_DIR/agent.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "No PID file found at $PID_FILE — agent may not be running."
    exit 0
fi

PID=$(cat "$PID_FILE")

if ! kill -0 "$PID" 2>/dev/null; then
    echo "Process $PID is not running. Removing stale PID file."
    rm -f "$PID_FILE"
    exit 0
fi

echo "Stopping Greenplum AI Agent (PID $PID)..."
kill -TERM "$PID"

# Wait up to 30 seconds for graceful shutdown
for i in $(seq 1 30); do
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "Agent stopped gracefully."
        rm -f "$PID_FILE"
        exit 0
    fi
    sleep 1
done

echo "Agent did not stop within 30s — forcing stop."
kill -KILL "$PID" 2>/dev/null
rm -f "$PID_FILE"
