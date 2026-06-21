#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build the amsha-tool-runner Podman image for the sandbox environment"
$ErrorActionPreference = 'Stop'
if (-not (Get-Command podman -ErrorAction SilentlyContinue)) {
    Write-Host '❌  podman is not installed or not in PATH.'
    exit 1
}
$dockerfile = './sandbox/Dockerfile.toolrunner'
if (-not (Test-Path $dockerfile)) {
    Write-Host "❌  Dockerfile not found at $dockerfile"
    exit 1
}
Write-Host '▶   Building amsha-tool-runner image via Podman...'
podman build --no-cache -t amsha-tool-runner -f $dockerfile .
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌  Podman build failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}
Write-Host ''
Write-Host '✅  Build complete. Verifying image in local registry:'
podman images | Select-String 'amsha-tool-runner'
