#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build and run all tests"
$ErrorActionPreference = 'Stop'
$mvnw = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
$mOpts = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow'

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

# --- Shield .env (secrets) from the tests ---
$envBackup = $null
if (Test-Path '.env') {
    Rename-Item '.env' '.env.build-backup'
    $envBackup = $true
    Write-Host 'ℹ️   .env shielded (.env.build-backup) - tests will use clean defaults.'
}

# --- Ensure env-example is a real copy (not symlink) for native-image ---
$envExampleSrc = '.env.example'
$envExampleDst = 'app/src/main/resources/env-example'
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
} else {
    Write-Host '⚠️   .env.example not found — skipping copy (native build may fail).'
}

    try {
        # Phase 0: Format code (Spotless + FQN replacement)
        python3 ./scripts/fix_fqns.py
        & $mvnw spotless:apply --no-transfer-progress -q
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host '✅  Code formatted (Spotless + FQN replacement).'

        $env:MAVEN_OPTS = "$mOpts -Xmx${jitGB}g -Xms${jitGB}g"
        $env:MAVEN_ARGS = "-ntp"
        & $mvnw clean verify -am "-Dquarkus.test.profile=test"
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host '✅  Tests complete.'
    } finally {
    if ($envBackup -and (Test-Path '.env.build-backup')) {
        Rename-Item '.env.build-backup' '.env'
        Write-Host 'ℹ️   .env restored.'
    }
}
