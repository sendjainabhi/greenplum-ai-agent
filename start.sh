#!/bin/bash
# Always run from the script's own directory so user.dir matches the app directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---------------------------------------------------------------------------
# 1. Build
# ---------------------------------------------------------------------------
echo "============================================================"
echo "  Greenplum AI Agent — Build & Start"
echo "============================================================"
echo ""
echo "[1/3] Building JAR (mvn clean package -DskipTests)..."

mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo ""
    echo "ERROR: Maven build failed. Fix the errors above and try again." >&2
    exit 1
fi
echo "      Build successful."

# ---------------------------------------------------------------------------
# 2. Replace JAR in project root
# ---------------------------------------------------------------------------
echo "[2/3] Copying new JAR to project root..."
# Remove any old JARs in the project root first
rm -f "$SCRIPT_DIR"/greenplum-ai-agent-*.jar
cp target/greenplum-ai-agent-*.jar "$SCRIPT_DIR/"
JAR=$(ls -t "$SCRIPT_DIR"/greenplum-ai-agent-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "ERROR: JAR not found after copy — build may have failed." >&2
    exit 1
fi
echo "      JAR ready: $(basename "$JAR")"

# ---------------------------------------------------------------------------
# 3. Start the agent
# ---------------------------------------------------------------------------
DATA_DIR="${AGENT_DATA_DIR:-$SCRIPT_DIR}"
mkdir -p "$DATA_DIR"

LOG_FILE="$DATA_DIR/stdout.log"
PID_FILE="$DATA_DIR/agent.pid"

# Stop any previously running instance
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[3/3] Stopping previous instance (PID $OLD_PID)..."
        kill -TERM "$OLD_PID"
        for i in $(seq 1 10); do
            kill -0 "$OLD_PID" 2>/dev/null || break
            sleep 1
        done
        kill -0 "$OLD_PID" 2>/dev/null && kill -KILL "$OLD_PID" 2>/dev/null
    fi
    rm -f "$PID_FILE"
fi

echo "[3/3] Starting Greenplum AI Agent..."
echo "      JAR : $(basename "$JAR")"
echo "      Data: $DATA_DIR"
echo "      Log : $LOG_FILE"
echo ""

nohup java -jar "$JAR" > "$LOG_FILE" 2>&1 &
AGENT_PID=$!
echo "$AGENT_PID" > "$PID_FILE"

echo "============================================================"
echo "  Agent started (PID $AGENT_PID)"
echo "  Open http://localhost:8080 in your browser"
echo "  Logs: tail -f $LOG_FILE"
echo "============================================================"
