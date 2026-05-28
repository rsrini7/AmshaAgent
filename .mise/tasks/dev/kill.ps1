#!/usr/bin/env pwsh
#MISE description = "Kill any lingering Quarkus dev / quarkus-run.jar background processes"
$ErrorActionPreference = 'Stop'
Write-Host '── Scanning for lingering Quarkus processes ─────────────────────'
$jps = Get-Command jps -ErrorAction SilentlyContinue
if (-not $jps) { Write-Host '❌  jps not found - is a JDK active? Run: mise run jdk:status'; exit 1 }
$procs = & jps -l 2>&1 | Where-Object { $_ -match 'quarkus-run\.jar|quarkus\.jar|QuarkusDevModeMain' }

# Also scan for native binaries (§Cosmetic-Fix)
$nativeProcs = Get-Process -Name "arcclaw" -ErrorAction SilentlyContinue
if ($nativeProcs) {
    Write-Host "ℹ️   Found running native processes: $($nativeProcs.Name)"
    $nativeProcs | Stop-Process -Force
    Write-Host "✅  Killed native processes."
}

if (-not $procs -and -not $nativeProcs) {
    Write-Host '✅  No lingering Quarkus processes found.'
    exit 0
}
Write-Host 'Found:'
$procs | ForEach-Object { Write-Host "  $_" }
Write-Host ''
$procs | ForEach-Object {
    $procId = ($_ -split '\s+')[0]
    try {
        Stop-Process -Id $procId -Force
        Write-Host "✅  Killed PID $procId"
    } catch {
        Write-Host "⚠️   Could not kill PID $procId (already gone?)"
    }
}
Write-Host '────────────────────────────────────────────────────────────────'
