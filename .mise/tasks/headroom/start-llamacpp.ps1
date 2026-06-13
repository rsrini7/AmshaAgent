#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Llamacpp on port 8797"

$ErrorActionPreference = 'Stop'
$Port = 8797
$Provider = "llamacpp"
$PidFile = "$PSScriptRoot/pids/$Provider.pid"
$LogDir = "$PSScriptRoot/logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (Test-Path $PidFile) {
    $oldPid = Get-Content $PidFile
    $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "✅ Headroom proxy for Llamacpp already running (PID $oldPid) on port $Port"
        exit 0
    }
}

Write-Host "▶  Starting Headroom proxy for Llamacpp on port $Port ..."

$env:OPENAI_TARGET_API_URL = "http://localhost:9000/v1"

bash -c "PYTHONUNBUFFERED=1 HEADROOM_CONFIG_DIR=$PSScriptRoot/../../../.headroom/config HEADROOM_MODEL_LIMITS=$PSScriptRoot/../../../.headroom/config/models.json OPENAI_TARGET_API_URL=http://localhost:9000/v1 nohup uvx --python 3.12 --from 'headroom-ai[proxy,ml,code]' headroom proxy --port $Port --memory --code-aware > '$LogDir/$Provider.log' 2> '$LogDir/$Provider.err' &"

$maxRetries = 20
$listener = $null
for ($i = 0; $i -lt $maxRetries; $i++) {
    Start-Sleep -Seconds 1
    $listener = bash -c "lsof -i :$Port -t -sTCP:listen 2>/dev/null | head -1"
    if ($listener) { break }
}
if ($listener) {
    $listener | Set-Content $PidFile
    Write-Host "✅  Headroom proxy for Llamacpp started (PID $listener) on port $Port"
    Write-Host "    Logs: $LogDir/$Provider.log  (stdout)"
    Write-Host "    Errors: $LogDir/$Provider.err  (stderr)"
    exit 0
}

Write-Host "❌  Failed to start Headroom proxy for Llamacpp. Check logs:"
Write-Host "    $LogDir/$Provider.log  (stdout)"
Write-Host "    $LogDir/$Provider.err  (stderr)"
Remove-Item $PidFile -ErrorAction SilentlyContinue
exit 1
