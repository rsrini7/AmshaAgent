#!/usr/bin/env pwsh
#MISE description = "Verify the arcclaw-tool-runner sandbox image exists"
$ErrorActionPreference = 'Stop'
$img = (podman images 2>&1) | Select-String 'arcclaw-tool-runner'
if ($img) {
    Write-Host '✅  arcclaw-tool-runner image is present.'
    Write-Host "  $img"
} else {
    Write-Host '❌  Image not found. Run: mise run sandbox:build'
    exit 1
}
