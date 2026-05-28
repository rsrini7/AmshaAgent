#!/usr/bin/env pwsh
#MISE description = "Stop the observability stack (data volumes are preserved)"
$ErrorActionPreference = 'Stop'
podman compose down
Write-Host '✅  Stack stopped. Data volumes preserved.'
