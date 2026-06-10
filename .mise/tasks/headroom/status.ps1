#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Show status of all Headroom proxies"

$ErrorActionPreference = 'Stop'
$PidsDir = "$PSScriptRoot/pids"

$PortMap = @{
    "openrouter" = 8788;
    "groq" = 8789;
    "nvidia" = 8790;
    "cerebras" = 8791;
    "gemini" = 8792;
    "openai" = 8793;
    "ollama" = 8794;
    "omlx" = 8795;
}

if (-not (Test-Path $PidsDir)) {
    Write-Host "No Headroom proxies have been started."
    Write-Host "  Start all: mise run headroom:start-all"
    exit 0
}

$running = 0
$stopped = 0
$files = Get-ChildItem $PidsDir -Filter "*.pid"

if ($files.Count -eq 0) {
    Write-Host "No Headroom proxy PID files found."
    Write-Host "  Start all: mise run headroom:start-all"
    exit 0
}

Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  Headroom Proxy Status"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

foreach ($file in $files) {
    $name = $file.BaseName
    $pidVal = Get-Content $file.FullName
    $port = $PortMap[$name]
    $proc = Get-Process -Id $pidVal -ErrorAction SilentlyContinue
    if ($proc) {
        $uptime = (Get-Date) - $proc.StartTime
        $uptimeStr = "{0:D2}h {1:D2}m {2:D2}s" -f $uptime.Hours, $uptime.Minutes, $uptime.Seconds
        Write-Host "  ✅ $name  PID $pidVal  port $port  uptime $uptimeStr"
        $running++
    } else {
        Write-Host "  ❌ $name  PID $pidVal  port $port  NOT RUNNING"
        $stopped++
    }
}

Write-Host ""
Write-Host "  Running: $running  |  Stopped: $stopped"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host ""
