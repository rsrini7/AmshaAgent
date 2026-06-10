#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Stop all running Headroom proxies"

$ErrorActionPreference = 'Stop'
$PidsDir = "$PSScriptRoot/pids"

if (-not (Test-Path $PidsDir)) {
    Write-Host "No PIDs directory found. Nothing to stop."
    exit 0
}

$stopped = 0
$notFound = 0
$files = Get-ChildItem $PidsDir -Filter "*.pid"

if ($files.Count -eq 0) {
    Write-Host "No Headroom proxy PID files found. Nothing to stop."
    exit 0
}

foreach ($file in $files) {
    $name = $file.BaseName
    $pidVal = Get-Content $file.FullName
    $proc = Get-Process -Id $pidVal -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host "Stopping Headroom proxy for $name (PID $pidVal)..."
        Stop-Process -Id $pidVal -Force
        Write-Host "  ✅ Stopped"
        $stopped++
    } else {
        Write-Host "Headroom proxy for $name (PID $pidVal) not running"
        $notFound++
    }
    Remove-Item $file.FullName -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Summary: $stopped stopped, $notFound not running"
