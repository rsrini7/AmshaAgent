#!/usr/bin/env pwsh
# Shared launcher helpers for Headroom proxies.
# Dot-source this from every start-*.ps1 script so the version pin and
# Apple-Silicon MPS tuning live in one place.

$script:DefaultHeadroomVersion = '0.28.0'

function Get-HeadroomVersion {
    # .env override wins; otherwise use the built-in pin.
    return $env:HEADROOM_VERSION ?? $script:DefaultHeadroomVersion
}

function Test-HeadroomAppleSilicon {
    return $IsMacOS -and
        ([System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -eq
         [System.Runtime.InteropServices.Architecture]::Arm64)
}

function Get-HeadroomUvxFrom {
    $extras = @('proxy', 'ml', 'code')
    # v0.25.0 adds Apple-Silicon MPS embedding offload via the [pytorch-mps] extra.
    if (Test-HeadroomAppleSilicon) {
        $extras += 'pytorch-mps'
    }
    return "headroom-ai[$($extras -join ',')]==$(Get-HeadroomVersion)"
}

function Get-HeadroomEmbedderRuntimeEnv {
    # Explicit .env override wins over auto-detection.
    if ($null -ne $env:HEADROOM_EMBEDDER_RUNTIME) {
        return $env:HEADROOM_EMBEDDER_RUNTIME
    }
    if (Test-HeadroomAppleSilicon) {
        return 'pytorch_mps'
    }
    return $null
}

function Start-HeadroomProxy {
    param(
        [Parameter(Mandatory)][string]$Provider,
        [Parameter(Mandatory)][int]$Port,
        [string]$Backend,
        [string]$TargetApiUrl,
        [string[]]$ExtraArgs = @('--memory', '--code-aware')
    )

    $ErrorActionPreference = 'Stop'
    $PidFile = "$PSScriptRoot/pids/$Provider.pid"
    $LogDir = "$PSScriptRoot/logs"
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

    if (Test-Path $PidFile) {
        $oldPid = Get-Content $PidFile
        $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "✅ Headroom proxy for $Provider already running (PID $oldPid) on port $Port"
            exit 0
        }
    }

    Write-Host "▶  Starting Headroom proxy for $Provider on port $Port ..."

    $uvxFrom = Get-HeadroomUvxFrom
    $embedderRuntime = Get-HeadroomEmbedderRuntimeEnv
    $runtimePrefix = if ($embedderRuntime) { "HEADROOM_EMBEDDER_RUNTIME=$embedderRuntime " } else { '' }

    Write-Host "   Version:    $(Get-HeadroomVersion)"
    Write-Host "   uvx from:   $uvxFrom"
    if ($embedderRuntime) {
        Write-Host "   Runtime:    HEADROOM_EMBEDDER_RUNTIME=$embedderRuntime"
    } else {
        Write-Host "   Runtime:    (default embedder selection)"
    }
    if ($Backend) { Write-Host "   Backend:    $Backend" }
    if ($TargetApiUrl) { Write-Host "   Target URL: $TargetApiUrl" }
    Write-Host "   Extras:     $($ExtraArgs -join ' ')"

    $envParts = @(
        'PYTHONUNBUFFERED=1'
        "HEADROOM_CONFIG_DIR=$PSScriptRoot/../../../.headroom/config"
        "HEADROOM_MODEL_LIMITS=$PSScriptRoot/../../../.headroom/config/models.json"
    )
    if ($TargetApiUrl) {
        $envParts += "OPENAI_TARGET_API_URL=$TargetApiUrl"
    }
    $envString = $envParts -join ' '

    $backendArg = if ($Backend) { " --backend $Backend" } else { '' }
    $extraArgString = $ExtraArgs -join ' '

    $bashCmd = "$runtimePrefix$envString nohup uvx --python 3.12 --from '$uvxFrom' headroom proxy --port $Port$backendArg $extraArgString > '$LogDir/$Provider.log' 2> '$LogDir/$Provider.err' &"

    bash -c $bashCmd

    # Wait for the proxy to actually accept traffic. Cold starts (uvx package
    # install + model download) can take longer than the old 20-second window.
    $maxRetries = 60
    $listener = $null
    Start-Sleep -Seconds 2
    for ($i = 0; $i -lt $maxRetries; $i++) {
        $listener = bash -c "lsof -i :$Port -t -sTCP:listen 2>/dev/null | head -1"
        if ($listener) {
            $health = bash -c "curl -sf http://127.0.0.1:$Port/health >/dev/null 2>&1 && echo ok"
            if ($health -eq 'ok') { break }
        }
        Start-Sleep -Seconds 1
    }

    if ($listener) {
        $listener | Set-Content $PidFile
        Write-Host "✅  Headroom proxy for $Provider started (PID $listener) on port $Port"
        if ($TargetApiUrl) { Write-Host "    Target: $TargetApiUrl" }
        Write-Host "    Logs: $LogDir/$Provider.log  (stdout)"
        Write-Host "    Errors: $LogDir/$Provider.err  (stderr)"
        exit 0
    }

    Write-Host "❌  Failed to start Headroom proxy for $Provider. Check logs:"
    Write-Host "    $LogDir/$Provider.log  (stdout)"
    Write-Host "    $LogDir/$Provider.err  (stderr)"
    Remove-Item $PidFile -ErrorAction SilentlyContinue
    exit 1
}
