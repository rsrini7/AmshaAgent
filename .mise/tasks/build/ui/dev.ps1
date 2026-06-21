#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start UI dev server on :3000 with Vite proxy to backend :8080"
$ErrorActionPreference = 'Stop'
Push-Location arcclaw-ui
try {
  npm install
  Write-Host '🖥️  UI dev server starting on http://localhost:3000'
  Write-Host '    Proxy: /api → http://localhost:8080, /ws → ws://localhost:8080'
  Write-Host '    Make sure backend is running (arcclaw serve or mise run dev)'
  npm run dev
} finally {
  Pop-Location
}
