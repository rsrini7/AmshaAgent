#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Upload a protected GitHub App PEM to Amsha SecretVault"
#MISE dir = "{{config_root}}"
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path (Get-Location) 'scripts/upload_github_app_credential.py'
$pythonCommand = if (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } elseif (Get-Command python -ErrorAction SilentlyContinue) { 'python' } else { throw 'Python 3 is required for the GitHub credential upload task.' }

& $pythonCommand $scriptPath @args
exit $LASTEXITCODE
