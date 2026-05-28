#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "SonarQube token setup guide"
$ErrorActionPreference = 'Stop'

Write-Host '═══════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host '  SonarQube Token Setup Guide' -ForegroundColor Cyan
Write-Host '═══════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host ''
Write-Host 'Step 1: Open SonarQube' -ForegroundColor Yellow
Write-Host '  → http://localhost:9000'
Write-Host '  → Login: admin / admin'
Write-Host ''
Write-Host 'Step 2: Generate Token' -ForegroundColor Yellow
Write-Host '  → Click your profile icon (top right)'
Write-Host '  → Select "My Account"'
Write-Host '  → Go to "Security" tab'
Write-Host '  → Under "Generate Tokens", enter:'
Write-Host '      Name: arcclaw-scanner'
Write-Host '      Type: User Token'
Write-Host '      Expiration: 365 days (or never)'
Write-Host '  → Click "Generate"'
Write-Host ''
Write-Host 'Step 3: Save Token' -ForegroundColor Yellow
Write-Host '  → Copy the generated token (shown only once!)'
Write-Host '  → Add to your .env file:'
Write-Host ''
Write-Host '      SONAR_TOKEN=sqp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' -ForegroundColor Green
Write-Host ''
Write-Host '  → Or export in your shell:'
Write-Host '      export SONAR_TOKEN=sqp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
Write-Host ''
Write-Host 'Step 4: Run Analysis' -ForegroundColor Yellow
Write-Host '  mise run sonar-scan'
Write-Host ''
Write-Host '═══════════════════════════════════════════════════════' -ForegroundColor Cyan
Write-Host ''
Write-Host 'Current status:' -ForegroundColor Yellow
if ($env:SONAR_TOKEN) {
    Write-Host '  ✅ SONAR_TOKEN is set' -ForegroundColor Green
} else {
    Write-Host '  ❌ SONAR_TOKEN is NOT set' -ForegroundColor Red
}
Write-Host ''
