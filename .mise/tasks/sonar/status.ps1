#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Check SonarQube container status"
$ErrorActionPreference = 'Stop'
Write-Host '-- SonarQube container status ───────────────────'
$psArgs = @(
    'ps',
    '--filter', 'name=sonarqube',
    '--format', 'table {{.Names}}	{{.Status}}	{{.Ports}}'
)
$ps = & podman $psArgs 2>&1
if ($ps) { Write-Host $ps } else { Write-Host '  Not running' }
Write-Host ''
Write-Host '✅  Run: mise run sonar:scan  to analyze the project'
