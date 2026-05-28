#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
if (-not (Test-Path '.env')) {
    Write-Host '❌  .env not found. Run: mise run setup'
    exit 1
}
$issues = @()
$vars = @{}
Get-Content '.env' | Where-Object { $_ -match '^\s*[^#]' } | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $vars[$Matches[1].Trim()] = $Matches[2].Trim() }
}

$provider = if ($vars['ARCCLAW_LLM_PROVIDER']) { $vars['ARCCLAW_LLM_PROVIDER'] } else { 'groq' }
if ($provider -eq 'groq'       -and [string]::IsNullOrEmpty($vars['GROQ_API_KEY']))       { $issues += "GROQ_API_KEY is required for provider: $provider" }
if ($provider -eq 'openrouter' -and [string]::IsNullOrEmpty($vars['OPENROUTER_API_KEY'])) { $issues += "OPENROUTER_API_KEY is required for provider: $provider" }
if ($provider -eq 'openai'     -and [string]::IsNullOrEmpty($vars['OPENAI_API_KEY']))     { $issues += "OPENAI_API_KEY is required for provider: $provider" }
if ($vars['ARCCLAW_VAULT_PEPPER']     -like '*changeme*') { $issues += 'ARCCLAW_VAULT_PEPPER is still a placeholder' }
if ($vars['ARCCLAW_AUDIT_HMAC_KEY']   -like '*changeme*') { $issues += 'ARCCLAW_AUDIT_HMAC_KEY is still a placeholder' }
if ($vars['ARCCLAW_API_KEYS_INTERNAL'] -like '*changeme*') { $issues += 'ARCCLAW_API_KEYS_INTERNAL is still a placeholder' }
if ($issues.Count -gt 0) {
    Write-Host '⚠️   Issues found in .env:'
    $issues | ForEach-Object { Write-Host "    - $_" }
    exit 1
} else {
    Write-Host '✅  .env looks good - all required keys are set.'
}
