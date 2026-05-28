#!/usr/bin/env pwsh
$ErrorActionPreference = 'Stop'
podman logs -f sonarqube
