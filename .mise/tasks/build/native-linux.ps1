#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Native binary for Linux distribution - Podman container (produces Linux ELF, not for macOS/Windows)"
$ErrorActionPreference = 'Stop'
$mvnw   = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$mOpts  = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow'

# ── Platform guard ────────────────────────────────────────────────────────────
Write-Host 'ℹ️   build:native-linux: produces Linux ELF binary via Podman container.'
if ($IsMacOS -or $IsWindows) {
    $os = if ($IsMacOS) { 'macOS' } else { 'Windows' }
    Write-Host "⚠️   $os detected: output will NOT run locally. Use build:native-local instead."
    Write-Host '    Proceeding anyway (use case: cross-compilation for Linux distribution).'
}
$strategy = 'local'
if ($IsWindows) {
    Write-Host 'ℹ️   Windows: using local GraalVM (container build produces Linux ELF - unusable on Windows).'
} elseif ($env:AMSHA_NATIVE_BUILD -eq 'local') {
    Write-Host 'ℹ️   AMSHA_NATIVE_BUILD=local: using local GraalVM.'
} elseif ($env:AMSHA_NATIVE_BUILD -eq 'container') {
    if (Get-Command podman -ErrorAction SilentlyContinue) {
        Write-Host 'ℹ️   AMSHA_NATIVE_BUILD=container: using Podman container build.'
        $strategy = 'container'
    } else {
        Write-Host '❌  AMSHA_NATIVE_BUILD=container but podman not found.'
        Write-Host '    macOS:      mise run podman:init'
        Write-Host '    Linux/WSL2: sudo apt install podman  then  mise run podman:socket'
        exit 1
    }
} else {
    if (Get-Command podman -ErrorAction SilentlyContinue) {
        podman info 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host 'ℹ️   Podman detected and running → container build (portable binary, recommended).'
            $strategy = 'container'
        } else {
            Write-Host '⚠️   Podman installed but not running → falling back to local GraalVM.'
            Write-Host '    macOS: mise run podman:start  /  Linux/WSL2: mise run podman:socket'
        }
    } else {
        Write-Host '⚠️   Podman not found → using local GraalVM build.'
        Write-Host '    macOS: mise run podman:init  /  Linux/WSL2: sudo apt install podman'
        Write-Host '    To silence: set AMSHA_NATIVE_BUILD=local in .env'
    }
}
$containerFlag = if ($strategy -eq 'container') { '-Dquarkus.native.container-build=true' } else { '-Dquarkus.native.container-build=false' }

# ── .env shield ───────────────────────────────────────────────────────────────
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

if ($strategy -eq 'container') {
    Write-Host 'ℹ️   Container build: host GraalVM not needed - skipping JDK switch.'

    # ── Podman VM memory preflight ──────────────────────────────────────────
    # GraalVM analysis needs ~5.5 GB heap + ~1.5 GB OS/compiler overhead = ~7 GB minimum.
    # Recommend 8 GB VM so GraalVM has headroom without swapping.
    $vmMemMB = [int]((podman machine inspect podman-machine-default 2>&1 | Select-String '"Memory":\s*(\d+)').Matches[0].Groups[1].Value)
    if ($vmMemMB -lt 8192) {
        $newVMemMB = [Math]::Max(8192, $vmMemMB)
        Write-Host "⚠️  Podman VM has ${vmMemMB} MB RAM — native-image needs 8+ GB."
        Write-Host "    Auto-resizing Podman VM to ${newVMemMB} MB..."
        podman machine stop 2>&1 | Out-Null
        podman machine set podman-machine-default --memory $newVMemMB
        podman machine start
        $vmMemMB = $newVMemMB
    }
    Write-Host "✅  Podman VM memory: ${vmMemMB} MB"

    try {
        # ── Memory Scaling (container-aware) ──────────────────────────────
        # Reserve 1.5 GB for OS/GraalVM compiler overhead inside the container.
        # GraalVM analysis (36k types, 168k methods) is allocation-heavy and
        # GC >80% of time if heap is too small.
        $overheadGB = 1.5
        $targetGB = [Math]::Max(4, [Math]::Floor(($vmMemMB / 1024) - $overheadGB))
        Write-Host "🧠 Allocating ${targetGB}G for native-image (VM ${vmMemMB}MB - ${overheadGB}GB overhead)..."

        $env:MAVEN_OPTS = $mOpts
        & $mvnw clean package -Pnative -pl app -am -DskipTests "-Damsha.persistence.backend=sqlite" "-Dquarkus.profile=prod" "-Dquarkus.native.native-image-xmx=${targetGB}g" $containerFlag
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        if ($args -contains '--clean' -or $args -contains '-c') {
            mise run dist:dist -- --clean
        } else {
            mise run dist:dist
        }
    } finally {
        if ($envBackup -eq $true -and (Test-Path '.env.build-backup')) { Rename-Item '.env.build-backup' '.env'; Write-Host 'ℹ️   .env restored.' }
        elseif ($envBackup -eq 'build.env') { Remove-Item '.env' -ErrorAction SilentlyContinue; Write-Host 'ℹ️   Temporary build.env copy removed.' }
    }
} else {
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
        $env:MAVEN_OPTS = $mOpts
        & $mvnw clean package -Pnative -pl app -am -DskipTests "-Damsha.persistence.backend=sqlite" "-Dquarkus.profile=prod" "-Dquarkus.native.native-image-xmx=${targetGB}g" $containerFlag
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        if ($args -contains '--clean' -or $args -contains '-c') {
            mise run dist:dist -- --clean
        } else {
            mise run dist:dist
        }
    } finally {
        if ($envBackup -eq $true -and (Test-Path '.env.build-backup')) { Rename-Item '.env.build-backup' '.env'; Write-Host 'ℹ️   .env restored.' }
        elseif ($envBackup -eq 'build.env') { Remove-Item '.env' -ErrorAction SilentlyContinue; Write-Host 'ℹ️   Temporary build.env copy removed.' }
        mise use --path mise.toml java@temurin-25
        Write-Host '✅  Switched back to Temurin 25.'
    }
}
Write-Host "✅  Native build complete [$strategy]: app/target/amsha-app-0.1.0-SNAPSHOT$suffix"
Write-Host ''
Write-Host '▶   Run with one of:'
Write-Host '      mise run native:serve                → HTTP + MCP server on :8080'
Write-Host '      mise run native:chat                 → interactive REPL'
Write-Host '      mise run native:attach               → Telegram daemon'
Write-Host '      $env:GOAL="..."; mise run native:ask → single-shot query'
