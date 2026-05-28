#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
Write-Host '▶   Starting SonarQube container...'

# Check if already running
$running = podman ps --format '{{.Names}}' 2>&1 | Select-String '^sonarqube$'
if ($running) {
    Write-Host '✅  SonarQube already running.'
    Write-Host '    Web UI: http://localhost:9000 (admin/admin)'
    exit 0
}

# Pull image if not present
podman image exists sonarqube:latest
if ($LASTEXITCODE -ne 0) {
    Write-Host '📦  Pulling sonarqube:latest image...'
    podman pull sonarqube:latest
}

# Create network if not exists
podman network exists arcclaw-net
if ($LASTEXITCODE -ne 0) {
    podman network create arcclaw-net 2>&1 | Out-Null
}

# Start the container (single line to avoid parsing issues)
podman run -d --name sonarqube -p 9000:9000 -e SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true -v sonarqube_data:/opt/sonarqube/data -v sonarqube_extensions:/opt/sonarqube/extensions -v sonarqube_logs:/opt/sonarqube/logs --network arcclaw-net sonarqube:latest

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ''
Write-Host '✅  SonarQube started. Web UI: http://localhost:9000'
Write-Host '    Default credentials: admin / admin'
Write-Host '    Wait ~30 seconds for first startup...'
