#!/bin/bash
echo "Starting Greenplum AI Agent in daemon mode..."

# Runs the JAR in the background, logs output, and saves the Process ID (PID)
nohup java -jar greenplum-ai-agent-1.0.0.jar > stdout.log 2>&1 &

echo "Agent started successfully in the background."