#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'

$commentActivate   = '# mise - ArcClaw: activate (added by setup:profile)'
$commentCompletion = '# mise - ArcClaw: tab completion (added by setup:profile)'

if ($IsWindows) {
    # ── Windows: write to $PROFILE ────────────────────────────────────────────
    $profilePath = $PROFILE
    if (-not $profilePath) {
        $profilePath = "$env:USERPROFILE\Documents\PowerShell\Microsoft.PowerShell_profile.ps1"
    }
    New-Item -ItemType Directory -Force (Split-Path $profilePath) | Out-Null
    Write-Host "-- Profile: $profilePath"

    $activateLine   = '(&mise activate pwsh) | Out-String | Invoke-Expression'
    $completionLine = 'try { mise completion powershell | Out-String | Invoke-Expression } catch {}'
    $encodingLine   = '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8'

    $content = if (Test-Path $profilePath) { [System.IO.File]::ReadAllText($profilePath) } else { '' }

    if ($content -match 'mise activate') {
        Write-Host "i   mise activate already present -- skipping."
    } else {
        Add-Content $profilePath "`n$commentActivate`n$activateLine"
        Write-Host "OK  mise activate written."
    }

    if ($content -match 'OutputEncoding') {
        Write-Host "i   Console UTF-8 encoding already present -- skipping."
    } else {
        Add-Content $profilePath "`n# Force UTF-8 for Windows Terminal / PowerShell (§Cosmetic-Fix)`n$encodingLine"
        Write-Host "OK  Console UTF-8 encoding written."
    }

    $content = if (Test-Path $profilePath) { [System.IO.File]::ReadAllText($profilePath) } else { '' }
    if ($content -match 'mise completion') {
        Write-Host "i   mise completion already present -- skipping."
    } else {
        Add-Content $profilePath "`n$commentCompletion`n$completionLine"
        Write-Host "OK  mise completion written."
    }

    Write-Host ""
    Write-Host "-- Written to $profilePath --"
    Get-Content $profilePath | Select-String -Pattern 'mise activate|mise completion'
    Write-Host ""
    Write-Host "Next steps:"
    Write-Host "  1. Reload:  . `$PROFILE"
    Write-Host "  2. Run:     mise tasks          <- shows all ArcClaw tasks"
    Write-Host "              mise run <TAB>       <- tab completion works"

} else {
    # ── Linux / macOS / WSL2: write to ~/.bashrc or ~/.zshrc ─────────────────
    $shell = $env:SHELL
    if ($shell -like '*zsh') {
        $rcFile     = "$env:HOME/.zshrc"
        $shellName  = 'zsh'
        $activateLine   = 'eval "$(mise activate zsh)"'
        $completionLine = 'autoload -Uz compinit && compinit; eval "$(mise completion zsh)"'
    } else {
        $rcFile     = "$env:HOME/.bashrc"
        $shellName  = 'bash'
        $activateLine   = 'eval "$(mise activate bash)"'
        $completionLine = 'eval "$(mise completion bash --include-bash-completion-lib)"'
    }
    Write-Host "-- Shell detected: $shellName -> $rcFile"

    $content = if (Test-Path $rcFile) { [System.IO.File]::ReadAllText($rcFile) } else { '' }

    if ($content -match 'mise activate') {
        Write-Host "i   mise activate already present -- skipping."
    } else {
        Add-Content $rcFile "`n$commentActivate`n$activateLine"
        Write-Host "OK  mise activate written."
    }

    $content = if (Test-Path $rcFile) { [System.IO.File]::ReadAllText($rcFile) } else { '' }
    if ($content -match 'mise completion') {
        Write-Host "i   mise completion already present -- skipping."
    } else {
        Add-Content $rcFile "`n$commentCompletion`n$completionLine"
        Write-Host "OK  mise completion written."
    }

    Write-Host ""
    Write-Host "-- Written to $rcFile --"
    Get-Content $rcFile | Select-String -Pattern 'mise activate|mise completion'
    Write-Host ""
    Write-Host "Next steps:"
    Write-Host "  1. Reload:  source $rcFile"
    Write-Host "  2. Run:     mise tasks          <- shows all ArcClaw tasks"
    Write-Host "              mise run <TAB>       <- tab completion works"
}
