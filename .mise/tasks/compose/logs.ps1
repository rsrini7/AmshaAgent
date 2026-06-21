#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Follow live amsha container logs (Ctrl+C to stop)"
podman compose logs -f amsha
