#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Openai on port 8793"

$ErrorActionPreference = 'Stop'
$Port = 8793
$Provider = "openai"
$PidFile = "$PSScriptRoot/pids/$Provider.pid"
$LogDir = "$PSScriptRoot/logs"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

if (Test-Path $PidFile) {
    $oldPid = Get-Content $PidFile
    $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "✅ Headroom proxy for Openai already running (PID $oldPid) on port $Port"
        exit 0
    }
}

Write-Host "▶  Starting Headroom proxy for Openai on port $Port ..."

$env:PYTHONUNBUFFERED = "1"
$env:OPENAI_TARGET_API_URL = "https://api.openai.com/v1"

$proc = Start-Process -FilePath "uvx" `
    -ArgumentList "--python", "3.12", "--from", "headroom-ai[proxy,ml,code]", "headroom", "proxy", "--port", "$Port", "--code-aware" `
    -RedirectStandardOutput "$LogDir/$Provider.log" `
    -RedirectStandardError "$LogDir/$Provider.log" `
    -PassThru

$proc.Id | Set-Content $PidFile

Start-Sleep -Seconds 1
$check = Get-Process -Id $proc.Id -ErrorAction SilentlyContinue
if (-not $check) {
    Write-Host "❌  Failed to start Headroom proxy for Openai. Check logs:"
    Write-Host "    $LogDir/$Provider.log"
    Remove-Item $PidFile -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "✅  Headroom proxy for Openai started (PID $($proc.Id)) on port $Port"
Write-Host "    Logs: $LogDir/$Provider.log"
