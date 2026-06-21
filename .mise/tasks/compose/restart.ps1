#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Restart only the amsha service (observability stack stays up)"
$ErrorActionPreference = 'Stop'
podman compose restart amsha
Write-Host '✅  amsha service restarted.'
