#!/usr/bin/env pwsh
#MISE description = "Check observability services status"
podman compose ps otel-collector jaeger phoenix prometheus 2>&1
