#!/usr/bin/env pwsh
#MISE description = "Native binary for PostgreSQL backend (arcclaw-pg) - local GraalVM, no container"
$ErrorActionPreference = 'Stop'
$mvnw   = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
Write-Host 'ℹ️   build:native-local-pg: PostgreSQL backend, local GraalVM, no Podman container.'

# --- Auto-Heal stranded backups before shielding ---
if (Test-Path '.env.build-backup') {
    if (Test-Path '.env') { Remove-Item '.env' -Force }
    Rename-Item '.env.build-backup' '.env'
    Write-Host '⚠️  Recovered stranded .env.build-backup before starting new build.'
}

$envBackup = $null
if (Test-Path '.env') {
    Rename-Item '.env' '.env.build-backup'
    $envBackup = $true
    Write-Host 'ℹ️   .env shielded (.env.build-backup) - secrets will not be baked into binary.'
} elseif (Test-Path 'build.env') {
    Copy-Item 'build.env' '.env'
    $envBackup = 'build.env'
    Write-Host 'ℹ️   build.env activated for native compilation (no secrets).'
} else {
    Write-Host 'ℹ️   No .env found - building without config override (good for CI).'
}
try {
    # ── Memory Scaling ──────────────────────────────────────────────────
    if ($IsMacOS) {
        $vm = vm_stat; $ps = [int](($vm | Select-String 'page size of (\d+)').Matches[0].Groups[1].Value)
        $availGB = [Math]::Floor(([int](($vm | Select-String 'Pages free:').ToString() -replace '.*:\s+(\d+)\.','$1') + [int](($vm | Select-String 'Pages inactive:').ToString() -replace '.*:\s+(\d+)\.','$1')) * $ps / 1GB)
        if ($availGB -lt 4) { $availGB = [Math]::Floor([int64](sysctl -n hw.memsize) / 1GB) }
    } elseif ($IsWindows) {
        $availGB = [Math]::Floor([System.GC]::GetGCMemoryInfo().TotalAvailableMemoryBytes / 1GB)
    } else {
        $availGB = [Math]::Floor([int64]((Select-String 'MemAvailable:' /proc/meminfo).ToString() -replace '.*:\s+(\d+)\s+kB','$1') / 1MB)
    }
    $targetGB = [Math]::Max(4, [Math]::Floor($availGB * 0.85))
    Write-Host "🧠 Allocating ${targetGB}G for native-image (85% of ${availGB}G available RAM)..."

    mise use --path mise.toml java@graalvm-community-25.0.2
    $graalPath = (mise where java@graalvm-community-25.0.2).Trim()
    $env:GRAALVM_HOME = $graalPath
    $env:JAVA_HOME = $graalPath

    $env:MAVEN_OPTS = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow'
    & $mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests "-Darcclaw.build.backend=postgres" "-Darcclaw.persistence.backend=postgres" "-Dquarkus.profile=prod,postgresql" '-Dquarkus.native.container-build=false' "-Dquarkus.native.native-image-xmx=${targetGB}g"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "✅  PostgreSQL native build complete: arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT$suffix"
    Write-Host '▶   Copying to dist/arcclaw-pg...'
    if (-not (Test-Path 'dist')) { New-Item -ItemType Directory -Path 'dist' -Force | Out-Null }
    # Clean up any old -runner files first
    $pgRunner = if ($IsWindows) { "dist/arcclaw-pg-runner.exe" } else { "dist/arcclaw-pg-runner" }
    $pgBinary = if ($IsWindows) { "dist/arcclaw-pg.exe" } else { "dist/arcclaw-pg" }
    if (Test-Path $pgRunner) { Remove-Item $pgRunner -Force }
    Copy-Item "arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT$suffix" $pgBinary -Force
    # Create .env for the PostgreSQL backend (activates %postgresql profile at runtime)
    $envContent = @"
# ArcClaw PostgreSQL backend configuration.
# QUARKUS_PROFILE is baked into the binary; only add runtime overrides here.
"@
    Set-Content -Path "dist/.env" -Value $envContent -NoNewline
    Write-Host '✅  PostgreSQL binary: dist/arcclaw-pg'
} finally {
    if ($envBackup -eq $true -and (Test-Path '.env.build-backup')) { Rename-Item '.env.build-backup' '.env'; Write-Host 'ℹ️   .env restored.' }
    elseif ($envBackup -eq 'build.env') { Remove-Item '.env' -ErrorAction SilentlyContinue; Write-Host 'ℹ️   Temporary build.env copy removed.' }
    mise use --path mise.toml java@temurin-25
    Write-Host '✅  Switched back to Temurin 25.'
}
