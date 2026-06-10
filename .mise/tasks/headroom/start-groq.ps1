#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Groq on port 8789"

$ErrorActionPreference = 'Stop'
$Port = 8789
$Provider = "groq"
$PidFile = "$PSScriptRoot/pids/$Provider.pid"
$LogDir = "$PSScriptRoot/logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (Test-Path $PidFile) {
    $oldPid = Get-Content $PidFile
    $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "✅ Headroom proxy for Groq already running (PID $oldPid) on port $Port"
        exit 0
    }
}

Write-Host "▶  Starting Headroom proxy for Groq on port $Port ..."

$env:OPENAI_TARGET_API_URL = "https://api.groq.com/openai/v1"

bash -c "PYTHONUNBUFFERED=1 OPENAI_TARGET_API_URL=https://api.groq.com/openai/v1 nohup uvx --python 3.12 --from 'headroom-ai[proxy,ml,code]' headroom proxy --port 8789 --code-aware > '$LogDir/$Provider.log' 2> '$LogDir/$Provider.err' &"

Start-Sleep -Seconds 2

$listener = bash -c "lsof -i :$Port -t -sTCP:listen 2>/dev/null | head -1"
if ($listener) {
    $listener | Set-Content $PidFile
    Write-Host "✅  Headroom proxy for Groq started (PID $listener) on port $Port"
    Write-Host "    Logs: $LogDir/$Provider.log  (stdout)"
    Write-Host "    Errors: $LogDir/$Provider.err  (stderr)"
    exit 0
}

Write-Host "❌  Failed to start Headroom proxy for Groq. Check logs:"
Write-Host "    $LogDir/$Provider.log  (stdout)"
Write-Host "    $LogDir/$Provider.err  (stderr)"
Remove-Item $PidFile -ErrorAction SilentlyContinue
exit 1
