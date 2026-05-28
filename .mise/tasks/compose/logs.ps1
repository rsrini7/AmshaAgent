#!/usr/bin/env pwsh
#MISE description = "Follow live arcclaw container logs (Ctrl+C to stop)"
podman compose logs -f arcclaw
