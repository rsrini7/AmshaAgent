#!/usr/bin/env pwsh
#MISE description = "macOS: stop the Podman machine"
$ErrorActionPreference = 'Stop'
if ($IsWindows) { Write-Host 'ℹ️   podman:stop is macOS-only.'; exit 0 }
podman machine stop
Write-Host '✅  Podman machine stopped.'
