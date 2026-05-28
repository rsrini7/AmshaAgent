#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description="First-time setup: interactive .env wizard, chmod +x mvnw, install JDKs"
#MISE quiet=true
# NOTE: No -NonInteractive flag - Read-Host requires an interactive shell.
# NOTE: #MISE dir= templating may not expand in file tasks depending on mise version.
#       We resolve project root from $PSScriptRoot as a fallback.
$ErrorActionPreference = 'Stop'

# Resolve project root robustly (two levels up from .mise/tasks/)
$projectRoot = Resolve-Path "$PSScriptRoot/../.."
Set-Location $projectRoot

$envPath = Join-Path $projectRoot '.env'

if (-not (Test-Path $envPath)) {
    Write-Host "`n  -- ArcClaw Local Developer Setup ------------------------------------------"

    $envExample = Join-Path $projectRoot '.env.example'
    if (Test-Path $envExample) { Copy-Item $envExample $envPath }
    else { New-Item $envPath -ItemType File | Out-Null }

    # ── Helper functions ──────────────────────────────────────────────────────
    function New-RandomBase64 { [Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Max 256) })) }
    function Set-EnvLine($file, $key, $value) {
        $c = [System.IO.File]::ReadAllText($file)
        if ($c -match "(?m)^$key=.*") { $c = $c -replace "(?m)^$key=.*", "$key=$value" }
        else { $c += "`n$key=$value" }
        [System.IO.File]::WriteAllText($file, $c)
    }
    function Clear-EnvLine($file, $key) {
        $c = [System.IO.File]::ReadAllText($file)
        $c = $c -replace "(?m)^$key=.*", "# $key="
        [System.IO.File]::WriteAllText($file, $c)
    }

    # ── FIX Bug 2: use Set-EnvLine to replace existing placeholder values ─────
    # Append-style would leave duplicate keys if .env.example had changeme values.
    Set-EnvLine $envPath 'ARCCLAW_VAULT_PEPPER'     (New-RandomBase64)
    Set-EnvLine $envPath 'ARCCLAW_AUDIT_HMAC_KEY'   (New-RandomBase64)
    Set-EnvLine $envPath 'ARCCLAW_API_KEYS_INTERNAL' (New-RandomBase64)
    Set-EnvLine $envPath 'ARCCLAW_API_KEYS_CI'       (New-RandomBase64)
    Write-Host "  OK  Auto-generated: VAULT_PEPPER - AUDIT_HMAC_KEY - API_KEYS`n"

    # ── FIX Bug 4: call shared wizard via $PSScriptRoot, not a bare relative path
    & "$PSScriptRoot/../shared/prompt-llm-wizard.ps1" -envPath $envPath

    # ── Telegram (dev-only: simplified, optional) ─────────────────────────────
    $tgTokenPeek = Read-Host "  TELEGRAM_BOT_TOKEN  (Enter to skip and disable Telegram for local dev)"
    if (-not $tgTokenPeek) {
        Set-EnvLine $envPath 'ARCCLAW_TELEGRAM_ENABLED' 'false'
        Clear-EnvLine $envPath 'TELEGRAM_BOT_TOKEN'
        Clear-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USER_IDS'
        Clear-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USERS'
        Write-Host "  i   Telegram disabled. Run ./run.ps1 attach later to configure."
    } else {
        Set-EnvLine $envPath 'TELEGRAM_BOT_TOKEN' $tgTokenPeek
        Write-Host "  OK  TELEGRAM_BOT_TOKEN set."

        # ── FIX Bug 3: add validation loops matching the production launcher ──
        do {
            $rawIds = (Read-Host "  ARCCLAW_TELEGRAM_ALLOWED_USER_IDS  (numeric IDs, comma-separated)").Trim() -replace '\s', ''
            if ($rawIds -notmatch '^[0-9]+(,[0-9]+)*$') { Write-Host "  X  Numeric only, e.g. 123456789,987654321" }
        } while ($rawIds -notmatch '^[0-9]+(,[0-9]+)*$')
        Set-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USER_IDS' $rawIds
        Write-Host "  OK  ARCCLAW_TELEGRAM_ALLOWED_USER_IDS set."

        do {
            $rawUsers = (Read-Host "  ARCCLAW_TELEGRAM_ALLOWED_USERS  (usernames without @, comma-separated)").Trim() -replace '\s', ''
            if ($rawUsers -notmatch '^[A-Za-z0-9_]+(,[A-Za-z0-9_]+)*$') { Write-Host "  X  Letters, numbers and _ only" }
        } while ($rawUsers -notmatch '^[A-Za-z0-9_]+(,[A-Za-z0-9_]+)*$')
        Set-EnvLine $envPath 'ARCCLAW_TELEGRAM_ALLOWED_USERS' $rawUsers
        Write-Host "  OK  ARCCLAW_TELEGRAM_ALLOWED_USERS set."

        Write-Host "  OK  Telegram configured."
    }

    Write-Host "`n  ✅ Local .env configuration complete!"
    Write-Host "  ---------------------------------------------------------------------------`n"
} else {
    Write-Host 'ℹ️   .env already exists - skipping interactive setup.'
}

if (-not $IsWindows) {
    chmod +x mvnw
    Write-Host '✅  mvnw marked executable.'
}

Write-Host 'Installing JDKs (temurin-25 + graalvm-community-25.0.2)...'
mise install java@temurin-25
mise install java@graalvm-community-25.0.2 --quiet
Write-Host '✅  Both JDKs installed.'

Write-Host 'Installing usage globally (required for tab completion)...'
mise use --global github:jdx/usage@3.0.0
Write-Host '✅  Setup complete.'
