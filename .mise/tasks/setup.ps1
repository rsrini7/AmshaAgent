#!/usr/bin/env pwsh
#MISE description="First-time setup: interactive .env wizard, chmod +x mvnw, install JDKs"

$ErrorActionPreference = 'Stop'
$envPath = '.env'

if (-not (Test-Path $envPath)) {
    Write-Host "`n  -- ArcClaw Local Developer Setup ------------------------------------------"
    Copy-Item '.env.example' $envPath

    function New-RandomBase64 { [Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Max 256) })) }
    
    # 1. Generate local dev crypto keys
    $c = [System.IO.File]::ReadAllText($envPath)
    $c += "`nARCCLAW_VAULT_PEPPER=" + (New-RandomBase64)
    $c += "`nARCCLAW_AUDIT_HMAC_KEY=" + (New-RandomBase64)
    $c += "`nARCCLAW_API_KEY_INTERNAL=" + (New-RandomBase64)
    $c += "`nARCCLAW_API_KEY_CI=" + (New-RandomBase64)
    [System.IO.File]::WriteAllText($envPath, $c)
    Write-Host "  OK  Auto-generated: VAULT_PEPPER - AUDIT_HMAC_KEY - API_KEYS`n"

    # 2. Call shared interactive wizard
    & .mise/shared/prompt-llm-wizard.ps1 -envPath $envPath

    Write-Host "  ✅ Local .env configuration complete!"
    Write-Host "  ---------------------------------------------------------------------------`n"
} else {
    Write-Host 'ℹ️   .env already exists — skipping interactive setup.'
}

if (-not $IsWindows) {
    chmod +x mvnw
    Write-Host '✅  mvnw marked executable.'
}

Write-Host 'Installing JDKs (temurin-25 + graalvm-community-25.0.2)...'
mise install java@temurin-25
mise install java@graalvm-community-25.0.2 --quiet

Write-Host 'Installing usage globally (required for tab completion)...'
mise use --global github:jdx/usage@3.0.0
Write-Host '✅  Setup complete.'