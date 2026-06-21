#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Restart only the arcclaw service (observability stack stays up)"
$ErrorActionPreference = 'Stop'
podman compose restart arcclaw
Write-Host '✅  arcclaw service restarted.'
