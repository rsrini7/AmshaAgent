#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Generic openai on port 8796"

. "$PSScriptRoot/headroom-common.ps1"

$TargetUrl = $env:ARCCLAW_GENERIC_OPENAI_TARGET_URL
if (-not $TargetUrl) {
    Write-Host "⚠️  ARCCLAW_GENERIC_OPENAI_TARGET_URL not set."
    Write-Host "    Set it to the real OpenAI-compatible endpoint URL in .env before starting headroom:"
    Write-Host "      ARCCLAW_GENERIC_OPENAI_TARGET_URL=https://opencode.ai/zen/go/v1"
    exit 1
}

Start-HeadroomProxy -Provider 'generic-openai' -Port 8796 -TargetApiUrl $TargetUrl
