@echo off
echo Starting Greenplum AI Agent...
java -Dlogging.file.name=greenplum-agent.log -jar greenplum-ai-agent-1.0.0.jar
pause