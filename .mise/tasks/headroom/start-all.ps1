#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start all Headroom proxies for ArcClaw loop providers"

$ErrorActionPreference = 'Stop'

Write-Host ""
Write-Host "🚀  Starting all Headroom proxies for ArcClaw loop providers..."
Write-Host ""

# Helper to start an individual proxy
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
Start-HeadroomProxy "ollama"

Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  Headroom Proxy Summary"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  OpenRouter  → http://localhost:8788/v1  (backend: openrouter)"
Write-Host "  Groq        → http://localhost:8789/v1  (target: api.groq.com)"
Write-Host "  NVIDIA      → http://localhost:8790/v1  (target: integrate.api.nvidia.com)"
Write-Host "  Ollama      → http://localhost:8791/v1  (target: localhost:11434)"
Write-Host ""
Write-Host "📝  To use these proxies in ArcClaw, update your .env:"
Write-Host ""
Write-Host "    # ── Agent loop ──"
Write-Host "    ARCCLAW_OPENROUTER_BASE_URL=http://localhost:8788/v1"
Write-Host "    ARCCLAW_GROQ_BASE_URL=http://localhost:8789/v1"
Write-Host "    ARCCLAW_NVIDIA_BASE_URL=http://localhost:8790/v1"
Write-Host ""
Write-Host "    # ── Compaction loop ──"
Write-Host "    ARCCLAW_COMPACTION_GROQ_BASE_URL=http://localhost:8789/v1"
Write-Host "    ARCCLAW_COMPACTION_OLLAMA_BASE_URL=http://localhost:8791/v1"
Write-Host ""
Write-Host "    # ── Classifier loop ──"
Write-Host "    ARCCLAW_CLASSIFIER_GROQ_BASE_URL=http://localhost:8789/v1"
Write-Host "    ARCCLAW_CLASSIFIER_OLLAMA_BASE_URL=http://localhost:8791/v1"
Write-Host ""
Write-Host "    # ── Embedding loop ──"
Write-Host "    ARCCLAW_EMBEDDING_OLLAMA_BASE_URL=http://localhost:8791/v1"
Write-Host ""
Write-Host "  Status:  mise run headroom:status"
Write-Host "  Stop:    mise run headroom:stop-all"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host ""
