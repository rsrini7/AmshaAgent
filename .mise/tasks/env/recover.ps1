#!/usr/bin/env pwsh
#MISE description="Internal: Auto-recover stranded .env files from aborted builds"
#MISE quiet= true

$ErrorActionPreference = 'SilentlyContinue'
if (Test-Path '.env.build-backup') {
    if (Test-Path '.env') { Remove-Item '.env' -Force }
    Rename-Item '.env.build-backup' '.env'
    if (Test-Path '.env') {
        Write-Host '⚠️  Recovered stranded .env.build-backup from a previously aborted build.'
    }
}