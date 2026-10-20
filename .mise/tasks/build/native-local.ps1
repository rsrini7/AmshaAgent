#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Native binary - always local GraalVM, no container (fast, any OS) - auto-switches JDK"
$ErrorActionPreference = 'Stop'
$mvnw   = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
$suffix = if ($IsWindows) { '-runner.exe' } else { '-runner' }
Write-Host 'ℹ️   build:native-local: local GraalVM, no Podman container.'

# --- Auto-Heal stranded backups before shielding ---
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
        # ... jenv bypass ...
    $graalPath = (mise where java@graalvm-community-25.0.2).Trim()
    $env:GRAALVM_HOME = $graalPath
    $env:JAVA_HOME = $graalPath
    $nativeAdditionalBuildArgs = @()
    $nativeImageBuildArgs = @()
    if ($IsMacOS -and $env:PATH -like '*nix/store*') {
        $zlib = Get-ChildItem '/nix/store' -Filter 'libz.dylib' -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like '*-zlib-*/lib/libz.dylib' } |
            Select-Object -First 1
        if ($zlib) {
            $zlibLibDir = $zlib.DirectoryName
            if ($env:NIX_LDFLAGS -notlike "*$zlibLibDir*") {
                $env:NIX_LDFLAGS = "$env:NIX_LDFLAGS -L$zlibLibDir".Trim()
            }
            $nativeImageBuildArgs += "-H:CLibraryPath=$zlibLibDir"
            Write-Host "ℹ️   Added zlib linker path for Nix clang: $zlibLibDir"
        }
    }

$nativeImageParallelism = $env:AMSHA_NATIVE_IMAGE_PARALLELISM
if (-not [string]::IsNullOrWhiteSpace($nativeImageParallelism)) {
    if ($nativeImageParallelism -notmatch '^[1-9][0-9]*$') {
        throw 'AMSHA_NATIVE_IMAGE_PARALLELISM must be a positive integer.'
    }
    $nativeImageBuildArgs += "--parallelism=$nativeImageParallelism"
    Write-Host "ℹ️   Limiting native-image build parallelism to $nativeImageParallelism worker(s)."
}

if ($nativeImageBuildArgs.Count -gt 0) {
    # Quarkus splits this property on commas without trimming; keep the joined list whitespace-free.
    $nativeImageBuildArgs = @('-H:+ReportExceptionStackTraces') + $nativeImageBuildArgs
    $nativeAdditionalBuildArgs += "-Dquarkus.native.additional-build-args=$($nativeImageBuildArgs -join ',')"
}

mise run build:ui:copy
if ($LASTEXITCODE -ne 0) { throw "build:ui:copy failed with exit code $LASTEXITCODE." }

$env:MAVEN_OPTS = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow'
& $mvnw clean verify -Pnative -pl app -am -DskipTests "-Damsha.build.backend=sqlite" "-Damsha.persistence.backend=sqlite" "-Dquarkus.profile=prod" '-Dquarkus.native.container-build=false' "-Dquarkus.native.native-image-xmx=${targetGB}g" @nativeAdditionalBuildArgs
    if ($LASTEXITCODE -ne 0) { throw "SQLite native build failed with exit code $LASTEXITCODE." }
    Write-Host "✅  Native build complete [local]: app/target/amsha-app-0.1.0-SNAPSHOT$suffix"
    if ($args -contains '--clean' -or $args -contains '-c') {
        mise run dist:dist -- --clean
    } else {
        mise run dist:dist
    }
} finally {
    if ($envBackup -eq $true -and (Test-Path '.env.build-backup')) {
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
    elseif ($envBackup -eq 'build.env') { Remove-Item '.env' -ErrorAction SilentlyContinue; Write-Host 'ℹ️   Temporary build.env copy removed.' }
    mise use --path mise.toml java@temurin-25
    Write-Host '✅  Switched back to Temurin 25.'
}
