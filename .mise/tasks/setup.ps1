#!/usr/bin/env pwsh
#MISE description="First-time setup: interactive .env wizard, chmod +x mvnw, install JDKs"

$ErrorActionPreference = 'Stop'
$envPath = '.env'

if (-not (Test-Path $envPath)) {
    Write-Host "`n  -- ArcClaw Local Developer Setup ------------------------------------------"
    Copy-Item '.env.example' $envPath

    function New-RandomBase64 { [Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Max 256) })) }
    function Clear-EnvLine($file, $key) {
        $c = [System.IO.File]::ReadAllText($file)
        $c = $c -replace "(?m)^$key=.*", "# $key="
        [System.IO.File]::WriteAllText($file, $c)
    }
    function Set-EnvLine($file, $key, $value) {
        $c = [System.IO.File]::ReadAllText($file)
        if ($c -match "(?m)^$key=.*") { $c = $c -replace "(?m)^$key=.*", "$key=$value" } else { $c += "`n$key=$value" }
        [System.IO.File]::WriteAllText($file, $c)
    }
    
    # 1. Generate local dev crypto keys
    $c = [System.IO.File]::ReadAllText($envPath)
    $c += "`nARCCLAW_VAULT_PEPPER=" + (New-RandomBase64)
    $c += "`nARCCLAW_AUDIT_HMAC_KEY=" + (New-RandomBase64)
    $c += "`nARCCLAW_API_KEY_INTERNAL=" + (New-RandomBase64)
    $c += "`nARCCLAW_API_KEY_CI=" + (New-RandomBase64)
    [System.IO.File]::WriteAllText($envPath, $c)
    Write-Host "  OK  Auto-generated: VAULT_PEPPER - AUDIT_HMAC_KEY - API_KEYS`n"

    # 2. Call shared interactive LLM wizard
    & .mise/shared/prompt-llm-wizard.ps1 -envPath $envPath

    # 3. Telegram Developer Cleanup
    $tgTokenPeek = Read-Host "  TELEGRAM_BOT_TOKEN  (Enter to skip and disable Telegram for local dev)"
    if (-not $tgTokenPeek) {
        Set-EnvLine $envPath 'ARCCLAW_TELEGRAM_ENABLED' 'false'
        Clear-EnvLine $envPath 'TELEGRAM_BOT_TOKEN'
        Clear-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USER_IDS'
        Clear-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USERS'
        Write-Host "  i   Telegram disabled. Run ./run.ps1 attach later to configure."
    } else {
        Set-EnvLine $envPath 'TELEGRAM_BOT_TOKEN' $tgTokenPeek
        Set-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USER_IDS' (Read-Host "  ARCCLAW_TELEGRAM_ALLOWED_USER_IDS (numeric IDs)")
        Set-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USERS' (Read-Host "  ARCCLAW_TELEGRAM_ALLOWED_USERS (usernames without @)")
        Write-Host "  OK  Telegram Configured."
    }

    Write-Host "`n  ✅ Local .env configuration complete!"
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