#!/usr/bin/env pwsh
#MISE description = "View SonarQube container logs (follow mode)"
$ErrorActionPreference = 'Stop'
podman logs -f sonarqube
