@echo off
setlocal

set "AGENT_JAR=%~dp0tailcat-mesh-agent.jar"
if not exist "%AGENT_JAR%" (
    echo Error: %AGENT_JAR% was not found.>&2
    exit /b 1
)

java -jar "%AGENT_JAR%" %*
exit /b %ERRORLEVEL%
