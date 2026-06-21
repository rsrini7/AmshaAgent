#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start full stack: Amsha Agent native + Ollama + OTel + Jaeger + Phoenix + Prometheus"
$ErrorActionPreference = 'Stop'
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$runner = "app/target/app-1.0.0-SNAPSHOT$suffix"
if (-not (Test-Path $runner)) {
    Write-Host "❌  Native binary not found. Run: mise run build:native-local (or build:native-linux for Linux)"; exit 1
}
podman compose up -d
Write-Host ''
Write-Host '✅  Stack started:'
Write-Host '    Amsha Agent REST  -> http://localhost:8080'
Write-Host '    Jaeger UI     -> http://localhost:16686'
Write-Host '    Phoenix UI    -> http://localhost:6006'
Write-Host '    Prometheus    -> http://localhost:9090'
Write-Host '    Ollama        -> http://localhost:11434'
