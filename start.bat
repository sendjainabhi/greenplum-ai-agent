@echo off
REM Find the most recent JAR matching greenplum-ai-agent-*.jar
for /f "tokens=*" %%F in ('dir /b /o:-d greenplum-ai-agent-*.jar 2^>nul') do (
    set JAR=%%F
    goto :found
)
echo ERROR: No greenplum-ai-agent-*.jar found in current directory.
pause
exit /b 1

:found
echo Starting Greenplum AI Agent (%JAR%)...
java -jar %JAR%
pause
