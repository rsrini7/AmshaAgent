#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Gemini on port 8792 (native Gemini backend)"

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
Write-Host "    ⚠️  Gemini uses native API. Wire ArcClaw Gemini provider to"
Write-Host "        ARCCLAW_GEMINI_BASE_URL=http://localhost:$Port/v1beta"

$env:PYTHONUNBUFFERED = "1"

$proc = Start-Process -FilePath "uvx" `
    -ArgumentList "--python", "3.12", "--from", "headroom-ai[proxy,ml,code]", "headroom", "proxy", "--port", "$Port", "--backend", "gemini", "--code-aware" `
    -RedirectStandardOutput "$LogDir/$Provider.log" `
    -RedirectStandardError "$LogDir/$Provider.log" `
    -PassThru

$proc.Id | Set-Content $PidFile

Start-Sleep -Seconds 1
$check = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if (-not $check) {
    Write-Host "❌  Failed to start Headroom proxy for Gemini. Check logs:"
    Write-Host "    $LogDir/$Provider.log"
    Remove-Item $PidFile -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "✅  Headroom proxy for Gemini started (PID $($proc.Id)) on port $Port"
Write-Host "    Logs: $LogDir/$Provider.log"
