#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
Write-Host '▶   Stopping SonarQube container...'
podman stop sonarqube 2>&1 | Out-Null
podman rm sonarqube 2>&1 | Out-Null
Write-Host '✅  SonarQube stopped.'
