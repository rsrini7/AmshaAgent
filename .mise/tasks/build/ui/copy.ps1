#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build UI and copy to Quarkus resources"
$ErrorActionPreference = 'Stop'
if (-not (Test-Path 'ui')) { Write-Host '❌  ui/ not found.'; exit 1 }
Push-Location ui
try {
  npm install
  npm run build
} finally {
  Pop-Location
}
$target = "app/src/main/resources/META-INF/resources"
if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target -Force | Out-Null }
Copy-Item -Path "ui/dist/*" -Destination $target -Recurse -Force
Write-Host "✅  UI built and copied to $target"
