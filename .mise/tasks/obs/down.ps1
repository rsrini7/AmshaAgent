#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Stop observability stack: OTel + Jaeger + Phoenix + Prometheus"
podman compose down otel-collector jaeger phoenix prometheus
Write-Host '✅  Observability stack stopped.'
