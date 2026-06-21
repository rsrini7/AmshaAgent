#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "View SonarQube container logs (follow mode)"
$ErrorActionPreference = 'Stop'
podman logs -f sonarqube
