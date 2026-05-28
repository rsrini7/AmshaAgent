#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Run SonarQube analysis (requires SONAR_TOKEN in .env)"
$ErrorActionPreference = 'Stop'
Write-Host '▶   Running SonarQube analysis...'

# Check if SONAR_TOKEN is set
if (-not $env:SONAR_TOKEN) {
    Write-Host '⚠️   SONAR_TOKEN not set. Please:'
    Write-Host '    1. Login to http://localhost:9000 (admin/admin)'
    Write-Host '    2. Go to My Account → Security → Generate Tokens'
    Write-Host '    3. Create a token with "Execute Analysis" permission'
    Write-Host '    4. Set: export SONAR_TOKEN=your_token_here'
    Write-Host ''
    Write-Host '    Or add to .env: SONAR_TOKEN=your_token_here'
    exit 1
}

# Get SonarQube URL from env or use default
$sonarUrl = if ($env:SONAR_API_URL) { $env:SONAR_API_URL } else { 'http://localhost:9000' }

# Build arguments as array to avoid PowerShell parsing -D as parameter
$args = @(
    "-Dsonar.projectKey=arcclaw",
    "-Dsonar.sources=.",
    "-Dsonar.host.url=$sonarUrl",
    "-Dsonar.token=$env:SONAR_TOKEN",
    "-Dsonar.java.binaries=arcclaw-app/target/classes",
    "-Dsonar.exclusions=**/target/**,**/node_modules/**,arcclaw-ui/**"
)
& sonar-scanner $args

if ($LASTEXITCODE -ne 0) {
  Write-Host '⚠️   Analysis completed with warnings or errors.'
} else {
  Write-Host "✅  Analysis complete. View results at: $sonarUrl"
}
