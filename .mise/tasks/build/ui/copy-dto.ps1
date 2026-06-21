#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Ensure generated TypeScript DTOs exist (create stubs if missing)"
$ErrorActionPreference = 'Stop'
$modelsDir = "ui/src/api/generated/models"
$stubFiles = @(
  "AgentStatusDto.ts",
  "AgentEvent.ts",
  "SkillDto.ts",
  "McpServerRequest.ts"
)
$missing = $false
foreach ($f in $stubFiles) {
  if (-not (Test-Path "$modelsDir/$f")) { $missing = $true; break }
}
if (-not $missing) { Write-Host '✅  Generated DTOs already exist.'; exit 0 }

Write-Host 'ℹ️   Generated DTOs missing — creating stubs from build output...'
try {
  if (-not (Test-Path 'ui')) { Write-Host '❌  ui/ not found.'; exit 1 }
  Push-Location ui
  npm run generate-dto
} finally {
  Pop-Location
}

if (-not (Test-Path $modelsDir)) { New-Item -ItemType Directory -Path $modelsDir -Force | Out-Null }

foreach ($f in $stubFiles) {
  if (-not (Test-Path "$modelsDir/$f")) {
    Write-Host "⚠️   DTO $f still missing after generate-dto — creating manual stub."
    $name = $f -replace '\.[^.]*$', ''
    $content = @"
export interface $name {
}
"@
    Set-Content -Path "$modelsDir/$f" -Value $content
  }
}
Write-Host '✅  All generated DTOs present.'
