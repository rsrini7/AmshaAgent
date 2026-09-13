#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Stop all running Headroom proxies (by PID file + port fallback for orphans)"

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/headroom-common.ps1"
$repoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$PidsDir = Join-Path $repoRoot "data/headroom/pids"
$LegacyPidsDir = "$PSScriptRoot/pids"

$PortMap = @{
    "openrouter" = 8788
    "groq"       = 8789
    "nvidia"     = 8790
    "cerebras"   = 8791
    "gemini"     = 8792
    "openai"     = 8793
    "ollama"     = 8794
    "omlx"       = 8795
    "generic-openai" = 8796
    "llamacpp"   = 8797
}

$stopped = 0
$orphanKilled = 0
$notFound = 0

# ── Phase 1: kill by PID file ──
$searchDirs = @($PidsDir, $LegacyPidsDir)
foreach ($dir in $searchDirs) {
    if (Test-Path $dir) {
        $files = Get-ChildItem $dir -Filter "*.pid"
        foreach ($file in $files) {
            $name = $file.BaseName
            $pidVal = (Get-Content $file.FullName -Raw).Trim()
            $port = $PortMap[$name]
            $proc = $null
            $isExpectedProxy = $false
            if ($pidVal -match '^\d+$') {
                $proc = Get-Process -Id ([int]$pidVal) -ErrorAction SilentlyContinue
                $isExpectedProxy = Test-HeadroomProxyProcess -ProcessId ([int]$pidVal) -Port $port
            }
            if ($proc -and $isExpectedProxy) {
                Write-Host "Stopping Headroom proxy for $name (PID $pidVal)..."
                Stop-Process -Id $pidVal -Force
                Write-Host "  ✅ Stopped"
                $stopped++
            } elseif ($proc) {
                Write-Host "Refusing to stop unrelated process $name (PID $pidVal); removing stale PID file."
                $notFound++
            } else {
                Write-Host "Headroom proxy for $name (PID $pidVal) not running"
                $notFound++
            }
            Remove-Item $file.FullName -ErrorAction SilentlyContinue
        }
    }
}

# ── Phase 2: orphan cleanup via lsof ──
foreach ($entry in $PortMap.GetEnumerator()) {
    $name = $entry.Key
    $port = $entry.Value
    $orphanPid = bash -c "lsof -i :$port -t -sTCP:listen 2>/dev/null | head -1"
        if ($orphanPid -match '^\d+$' -and (Test-HeadroomProxyProcess -ProcessId ([int]$orphanPid) -Port $port)) {
        Write-Host "Found orphan proxy for $name on port $port (PID $orphanPid) — killing..."
        bash -c "kill -9 $orphanPid 2>/dev/null"
        Write-Host "  ✅ Killed orphan"
        $orphanKilled++
        } elseif ($orphanPid) {
            Write-Host "Skipping unrelated listener on Headroom port $port (PID $orphanPid)."
        }
}

Write-Host ""
Write-Host "Summary: $stopped stopped by PID file, $orphanKilled orphans killed, $notFound not running"
