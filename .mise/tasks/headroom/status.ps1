#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Show status of all Headroom proxies"

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/headroom-common.ps1"
$repoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$PidsDir = Join-Path $repoRoot "data/headroom/pids"
$LegacyPidsDir = "$PSScriptRoot/pids"

$PortMap = @{
    "openrouter" = 8788;
    "groq" = 8789;
    "nvidia" = 8790;
    "cerebras" = 8791;
    "gemini" = 8792;
    "openai" = 8793;
    "ollama" = 8794;
    "omlx" = 8795;
    "generic-openai" = 8796;
    "llamacpp" = 8797;
}

function Get-ProcessEnvValue {
    param([int]$ProcessId, [string]$VarName)
    $raw = bash -c "ps eww $ProcessId 2>/dev/null | grep -o '${VarName}=[^ ]*' | head -1"
    if ($raw -and $raw.StartsWith("${VarName}=")) {
        return $raw.Substring("${VarName}=".Length)
    }
    return $null
}

function Get-HeadroomActualBackend {
    param([int]$ProcessId, [string]$ProviderName)

    # Some providers pass an explicit --backend flag.
    $cmdline = bash -c "ps -p $ProcessId -o args= 2>/dev/null"
    if ($cmdline -match '--backend\s+(\S+)') {
        return $Matches[1]
    }

    # OpenAI-compatible providers set OPENAI_TARGET_API_URL instead.
    $openaiUrl = Get-ProcessEnvValue -ProcessId $ProcessId -VarName 'OPENAI_TARGET_API_URL'
    if ($openaiUrl) {
        switch -Regex ($openaiUrl) {
            'api\.openai\.com' { return 'openai' }
            'api\.groq\.com' { return 'groq' }
            'integrate\.api\.nvidia\.com' { return 'nvidia' }
            'api\.cerebras\.ai' { return 'cerebras' }
            'localhost:11434' { return 'ollama' }
            '127\.0\.0\.1:8000' { return 'omlx' }
            'localhost:9000' { return 'llamacpp' }
            default { return 'openai-compatible' }
        }
    }

    return 'unknown'
}

$pidFiles = @()
if (Test-Path $PidsDir) {
    $pidFiles += Get-ChildItem $PidsDir -Filter "*.pid"
}
if (Test-Path $LegacyPidsDir) {
    $pidFiles += Get-ChildItem $LegacyPidsDir -Filter "*.pid"
}

if ($pidFiles.Count -eq 0) {
    Write-Host "No Headroom proxies have been started."
    Write-Host "  Start all: mise run headroom:start-all"
    exit 0
}

$running = 0
$stopped = 0
$files = $pidFiles

Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  Headroom Proxy Status"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

foreach ($file in $files) {
    $name = $file.BaseName
    $pidVal = Get-Content $file.FullName
    $port = $PortMap[$name]
        $proc = $null
        $isExpectedProxy = $false
        if ($pidVal -match '^\d+$') {
            $proc = Get-Process -Id ([int]$pidVal) -ErrorAction SilentlyContinue
            $isExpectedProxy = Test-HeadroomProxyProcess -ProcessId ([int]$pidVal) -Port $port
        }
        if ($proc -and $isExpectedProxy) {
        $uptime = (Get-Date) - $proc.StartTime
        $uptimeStr = "{0:D2}h {1:D2}m {2:D2}s" -f $uptime.Hours, $uptime.Minutes, $uptime.Seconds
        Write-Host "  ✅ $name  PID $pidVal  port $port  uptime $uptimeStr"

        $healthJson = bash -c "curl -sf http://127.0.0.1:$port/health 2>/dev/null || true"
        if ($healthJson) {
            try {
                $health = $healthJson | ConvertFrom-Json
                $version = $health.version ?? 'unknown'
                $ready = $health.ready ?? $false
                $actualBackend = Get-HeadroomActualBackend -ProcessId $pidVal -ProviderName $name
                $openaiUrl = $health.config.openai_api_url
                $upstreamUrl = $health.checks.upstream.url

                $readyMark = if ($ready) { 'ready' } else { 'not ready' }
                Write-Host "     version: $version | $readyMark | backend: $actualBackend"

                $targetUrls = @()
                if ($health.config.anthropic_api_url) { $targetUrls += "anthropic: $($health.config.anthropic_api_url)" }
                if ($health.config.openai_api_url) { $targetUrls += "openai: $($health.config.openai_api_url)" }
                if ($health.config.gemini_api_url) { $targetUrls += "gemini: $($health.config.gemini_api_url)" }
                if ($health.config.cloudcode_api_url) { $targetUrls += "cloudcode: $($health.config.cloudcode_api_url)" }
                foreach ($urlLine in $targetUrls) {
                    Write-Host "     target $urlLine"
                }
            } catch {
                Write-Host "     health: received non-JSON response"
            }
        } elseif ($proc) {
            Write-Host " ⚠️ $name PID $pidVal is not the expected Headroom proxy; refusing to inspect it."
        } else {
            Write-Host "     health: not reachable"
        }

        $embedderRuntime = Get-ProcessEnvValue -ProcessId $pidVal -VarName 'HEADROOM_EMBEDDER_RUNTIME'
        if ($embedderRuntime) {
            Write-Host "     runtime: HEADROOM_EMBEDDER_RUNTIME=$embedderRuntime"
        }

        $running++
    } else {
        Write-Host "  ❌ $name  PID $pidVal  port $port  NOT RUNNING"
        $stopped++
    }
}

Write-Host ""
Write-Host "  Running: $running  |  Stopped: $stopped"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host ""
