#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Verify the amsha-tool-runner sandbox image exists"
$ErrorActionPreference = 'Stop'
$img = (podman images 2>&1) | Select-String 'amsha-tool-runner'
if ($img) {
    Write-Host '✅  amsha-tool-runner image is present.'
    Write-Host "  $img"
} else {
    Write-Host '❌  Image not found. Run: mise run sandbox:build'
    exit 1
}
