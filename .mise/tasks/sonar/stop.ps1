#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Stop SonarQube container"
$ErrorActionPreference = 'Stop'
Write-Host '▶   Stopping SonarQube container...'
podman stop sonarqube 2>&1 | Out-Null
podman rm sonarqube 2>&1 | Out-Null
Write-Host '✅  SonarQube stopped.'
