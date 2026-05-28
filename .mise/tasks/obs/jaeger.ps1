#!/usr/bin/env pwsh
#MISE description = "Start only Jaeger + OTel collector"
podman compose up -d jaeger otel-collector
Write-Host '✅  Jaeger + OTel collector started. UI: http://localhost:16686'
