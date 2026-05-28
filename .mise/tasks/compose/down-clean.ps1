#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Stop the stack and DELETE all data volumes (irreversible - prompts for confirmation)"
$ErrorActionPreference = 'Stop'
Write-Host '⚠️   WARNING: This will permanently delete all data volumes.'
$confirm = Read-Host 'Type YES to confirm'
if ($confirm -eq 'YES') {
    podman compose down -v
    Write-Host '✅  Stack and volumes removed.'
} else {
    Write-Host 'Aborted.'
    exit 1
}
