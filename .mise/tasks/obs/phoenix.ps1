#!/usr/bin/env pwsh
#MISE description = "Start only Phoenix + OTel collector (span routing via collector)"
podman compose up -d phoenix otel-collector
Write-Host '✅  Phoenix + OTel collector started. UI: http://localhost:6006'
