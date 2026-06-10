#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start all Headroom proxies for ArcClaw loop providers"

$ErrorActionPreference = 'Stop'

Write-Host ""
Write-Host "🚀  Starting all Headroom proxies for ArcClaw loop providers..."
Write-Host ""

function Start-HeadroomProxy {
    param([string]$Name)
    & "$PSScriptRoot/start-$Name.ps1"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "⚠️  start-$Name.ps1 exited with code $LASTEXITCODE"
    }
}

Start-HeadroomProxy "openrouter"
Start-HeadroomProxy "groq"
Start-HeadroomProxy "nvidia"
Start-HeadroomProxy "cerebras"
Start-HeadroomProxy "gemini"
Start-HeadroomProxy "openai"
Start-HeadroomProxy "ollama"
Start-HeadroomProxy "omlx"

Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  Headroom Proxy Summary"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  openrouter  → http://localhost:8788/v1"
Write-Host "  groq        → http://localhost:8789/v1"
Write-Host "  nvidia      → http://localhost:8790/v1"
Write-Host "  cerebras    → http://localhost:8791/v1"
Write-Host "  gemini      → http://localhost:8792/v1"
Write-Host "  openai      → http://localhost:8793/v1"
Write-Host "  ollama      → http://localhost:8794/v1"
Write-Host "  omlx        → http://localhost:8795/v1"
Write-Host "  Memory:    ENABLED (persistent cross-session)"
Write-Host ""
Write-Host "📝  To use these proxies in ArcClaw, update your .env:"
Write-Host ""
Write-Host "    ARCCLAW_OPENROUTER_BASE_URL=http://localhost:8788/v1"
Write-Host "    ARCCLAW_GROQ_BASE_URL=http://localhost:8789/v1"
Write-Host "    ARCCLAW_NVIDIA_BASE_URL=http://localhost:8790/v1"
Write-Host "    ARCCLAW_CEREBRAS_BASE_URL=http://localhost:8791/v1"
Write-Host "    ARCCLAW_GEMINI_BASE_URL=http://localhost:8792/v1"
Write-Host "    ARCCLAW_OPENAI_BASE_URL=http://localhost:8793/v1"
Write-Host "    ARCCLAW_OLLAMA_BASE_URL=http://localhost:8794/v1"
Write-Host "    ARCCLAW_OMLX_BASE_URL=http://localhost:8795/v1"
Write-Host ""
Write-Host "  Status:  mise run headroom:status"
Write-Host "  Stop:    mise run headroom:stop-all"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host ""
