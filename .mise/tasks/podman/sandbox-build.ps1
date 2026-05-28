#!/usr/bin/env pwsh
#MISE description = "Build the arcclaw-tool-runner Podman image for the sandbox environment"
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
Write-Host '▶   Building arcclaw-tool-runner image via Podman...'
podman build --no-cache -t arcclaw-tool-runner -f $dockerfile .
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌  Podman build failed (exit $LASTEXITCODE)"
    exit $LASTEXITCODE
}
Write-Host ''
Write-Host '✅  Build complete. Verifying image in local registry:'
podman images | Select-String 'arcclaw-tool-runner'
