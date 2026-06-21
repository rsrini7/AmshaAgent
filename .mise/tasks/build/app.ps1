#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "JVM build - all modules, skip tests (fast daily driver) - uses temurin-25"
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
    Write-Host 'ℹ️   .env shielded (.env.build-backup) - secrets will not be baked into JAR.'
}

try {
    $env:MAVEN_OPTS = "$mOpts -Xmx${jitGB}g -Xms${jitGB}g"

    # Phase 1: Build Maven backend first (generates OpenAPI spec)
    & $mvnw clean install -pl app -am -DskipTests --no-transfer-progress -T $threads
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host '✅  Maven build complete. OpenAPI spec generated.'

    # Phase 2: Build UI (prebuild auto-generates TypeScript DTOs from OpenAPI spec)
    try {
        Push-Location ui
        npm install
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

    Write-Host '✅  Build complete: app/target/quarkus-app/quarkus-run.jar'
} finally {
    if ($envBackup -and (Test-Path '.env.build-backup')) {
        Rename-Item '.env.build-backup' '.env'
        Write-Host 'ℹ️   .env restored.'
    }
}
