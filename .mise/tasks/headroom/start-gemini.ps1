#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Gemini on port 8792"

$ErrorActionPreference = 'Stop'
$Port = 8792
$Provider = "gemini"
$PidFile = "$PSScriptRoot/pids/$Provider.pid"
$LogDir = "$PSScriptRoot/logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (Test-Path $PidFile) {
    $oldPid = Get-Content $PidFile
    $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "✅ Headroom proxy for Gemini already running (PID $oldPid) on port $Port"
        exit 0
    }
}

Write-Host "▶  Starting Headroom proxy for Gemini on port $Port ..."


bash -c "PYTHONUNBUFFERED=1 HEADROOM_CONFIG_DIR=$PSScriptRoot/../../../.headroom/config HEADROOM_MODEL_LIMITS=$PSScriptRoot/../../../.headroom/config/models.json nohup uvx --python 3.12 --from 'headroom-ai[proxy,ml,code]' headroom proxy --port 8792 --backend gemini --memory --code-aware > '$LogDir/$Provider.log' 2> '$LogDir/$Provider.err' &"

$maxRetries = 6
$listener = $null
for ($i = 0; $i -lt $maxRetries; $i++) {
    Start-Sleep -Seconds 1
    $listener = bash -c "lsof -i :$Port -t -sTCP:listen 2>/dev/null | head -1"
    if ($listener) { break }
}
if ($listener) {
    $listener | Set-Content $PidFile
    Write-Host "✅  Headroom proxy for Gemini started (PID $listener) on port $Port"
    Write-Host "    Logs: $LogDir/$Provider.log  (stdout)"
    Write-Host "    Errors: $LogDir/$Provider.err  (stderr)"
    exit 0
}

Write-Host "❌  Failed to start Headroom proxy for Gemini. Check logs:"
Write-Host "    $LogDir/$Provider.log  (stdout)"
Write-Host "    $LogDir/$Provider.err  (stderr)"
Remove-Item $PidFile -ErrorAction SilentlyContinue
exit 1