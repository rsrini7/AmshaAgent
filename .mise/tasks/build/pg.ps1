#!/usr/bin/env pwsh
#MISE description = "Clean install all modules with PostgreSQL backend"
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
if (Test-Path '.env') {
    Rename-Item '.env' '.env.build-backup'
    $envBackup = $true
    Write-Host 'ℹ️   .env shielded (.env.build-backup) - secrets will not be baked into build.'
}

try {
    $env:MAVEN_OPTS = "$mOpts -Xmx${jitGB}g -Xms${jitGB}g"
    & $mvnw clean install -am -DskipTests '-Dquarkus.profile=postgresql' --no-transfer-progress -T $threads
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host '✅  PostgreSQL build complete.'
} finally {
    if ($envBackup -and (Test-Path '.env.build-backup')) {
        Rename-Item '.env.build-backup' '.env'
        Write-Host 'ℹ️   .env restored.'
    }
}
