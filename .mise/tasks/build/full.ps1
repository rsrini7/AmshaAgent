#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Clean install all modules, skip tests"
$ErrorActionPreference = 'Stop'
$mvnw = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
$mOpts = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dquarkus.otel.sdk.disabled=true'

# --- Auto-tune: detect available RAM & CPUs ---
if ($IsMacOS) {
    $vm = vm_stat; $ps = [int](($vm | Select-String 'page size of (\d+)').Matches[0].Groups[1].Value)
    $availGB = [Math]::Floor(([int](($vm | Select-String 'Pages free:').ToString() -replace '.*:\s+(\d+)\.','$1') + [int](($vm | Select-String 'Pages inactive:').ToString() -replace '.*:\s+(\d+)\.','$1')) * $ps / 1GB)
    if ($availGB -lt 4) { $availGB = [Math]::Floor([int64](sysctl -n hw.memsize) / 1GB) }
    $cpu = [int](sysctl -n hw.ncpu)
} elseif ($IsWindows) {
    $availGB = [Math]::Floor([System.GC]::GetGCMemoryInfo().TotalAvailableMemoryBytes / 1GB)
    $cpu = [int]$env:NUMBER_OF_PROCESSORS
} else {
    $availGB = [Math]::Floor([int64]((Select-String 'MemAvailable:' /proc/meminfo).ToString() -replace '.*:\s+(\d+)\s+kB','$1') / 1MB)
    $cpu = [int](nproc)
}
$jitGB = [Math]::Max(2, [Math]::Floor($availGB * 0.5))
$threads = [Math]::Max(2, [Math]::Min([Math]::Floor($cpu / 2), 8))
Write-Host "🧠 ${availGB}G avail, ${cpu} CPUs → JIT heap:${jitGB}G, parallel threads:${threads}"

# --- Shield .env (secrets) from the build ---
$envBackup = $null
if (Test-Path '.env.build-backup') {
    if (Test-Path '.env') {
        $recoveryStamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
        $recoveryPath = ".env.recovery-$recoveryStamp"
        $recoveryIndex = 0
        while (Test-Path $recoveryPath) {
            $recoveryIndex++
            $recoveryPath = ".env.recovery-$recoveryStamp-$recoveryIndex"
        }
        Move-Item '.env' $recoveryPath
        Write-Host "⚠️  Preserved current .env as $recoveryPath before recovery."
    }
    Rename-Item '.env.build-backup' '.env'
    Write-Host '⚠️  Recovered stranded .env.build-backup before starting new build.'
}
if (Test-Path '.env') {
    Rename-Item '.env' '.env.build-backup'
    $envBackup = $true
    Write-Host 'ℹ️   .env shielded (.env.build-backup) - secrets will not be baked into build.'
}

# --- Ensure env-example is a real copy (not symlink) for native-image ---
$repoRoot = if ($env:MISE_PROJECT_ROOT -and (Test-Path "$env:MISE_PROJECT_ROOT/.env.example")) {
    $env:MISE_PROJECT_ROOT
} elseif ($PSScriptRoot -and (Test-Path "$PSScriptRoot/../../../.env.example")) {
    (Resolve-Path "$PSScriptRoot/../../..").Path
} elseif (Test-Path '.env.example') {
    (Get-Location).Path
} else {
    $null
}

$envExampleSrc = if ($repoRoot) { Join-Path $repoRoot '.env.example' } else { '.env.example' }
$envExampleDst = if ($repoRoot) { Join-Path $repoRoot 'app/src/main/resources/env-example' } else { 'app/src/main/resources/env-example' }
$srcItem = Get-Item $envExampleSrc -ErrorAction SilentlyContinue
if ($srcItem) {
    if ((Test-Path $envExampleDst) -and ((Get-Item $envExampleDst).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Remove-Item $envExampleDst -Force
    }
    $dstItem = Get-Item $envExampleDst -ErrorAction SilentlyContinue
    if (-not $dstItem -or $srcItem.LastWriteTime -gt $dstItem.LastWriteTime) {
        Copy-Item -Path $envExampleSrc -Destination $envExampleDst -Force
        Write-Host 'ℹ️   .env.example copied to app/src/main/resources/env-example'
    }
} elseif (Test-Path $envExampleDst) {
    Write-Host 'ℹ️   Using existing app/src/main/resources/env-example for native build.'
} else {
    Write-Host '⚠️   .env.example not found — skipping copy (native build may fail).'
}

try {
    $env:MAVEN_OPTS = "$mOpts -Xmx${jitGB}g -Xms${jitGB}g"
    $env:MAVEN_ARGS = "-ntp"

    # Phase 0: Format code (Spotless + FQN replacement)
    python3 ./scripts/fix_fqns.py
    & $mvnw spotless:apply --no-transfer-progress -q
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host '✅  Code formatted (Spotless + FQN replacement).'

    # Phase 1: Build Maven backend first (generates OpenAPI spec)
    & $mvnw clean install -am -DskipTests --no-transfer-progress -T $threads
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host '✅  Maven build complete. OpenAPI spec generated.'

    # Phase 2: Build UI (prebuild auto-generates TypeScript DTOs from OpenAPI spec)
    try {
        Push-Location ui
        Write-Host 'ℹ️   Building UI...'
        npm install --prefer-offline --no-audit
        npm run build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host '✅  UI built.'
    } finally {
        Pop-Location
    }

    # Phase 3: Copy UI dist to Quarkus resources
    $target = "app/src/main/resources/META-INF/resources"
    if (-not (Test-Path $target)) { New-Item -ItemType Directory -Path $target -Force | Out-Null }
    Copy-Item -Path "ui/dist/*" -Destination $target -Recurse -Force
    Write-Host '✅  UI copied to Quarkus resources.'

    # Repackage after the UI copy so the application artifacts contain the fresh bundle.
    & $mvnw verify -DskipTests --no-transfer-progress -T $threads
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host '✅  Full build complete.'
} finally {
    if ($envBackup -and (Test-Path '.env.build-backup')) {
        if (Test-Path '.env') {
            $recoveryStamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
            $recoveryPath = ".env.recovery-$recoveryStamp"
            $recoveryIndex = 0
            while (Test-Path $recoveryPath) {
                $recoveryIndex++
                $recoveryPath = ".env.recovery-$recoveryStamp-$recoveryIndex"
            }
            Move-Item '.env' $recoveryPath
            Write-Host "⚠️  Preserved current .env as $recoveryPath before restore."
        }
        Rename-Item '.env.build-backup' '.env'
        Write-Host 'ℹ️   .env restored.'
    }
}
