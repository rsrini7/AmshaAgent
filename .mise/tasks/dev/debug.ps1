#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Dev mode - serve on :8080 with hot reload + JDWP debug on :5005 (no Podman)"
#MISE dir = "{{config_root}}/app"
$ErrorActionPreference = 'Stop'

if ($IsWindows -and -not ([System.Management.Automation.PSTypeName]'WinCon').Type) {
    Add-Type -TypeDefinition '
        using System;
        using System.Runtime.InteropServices;
        public class WinCon {
            [DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int n);
            [DllImport("kernel32.dll")] public static extern bool SetConsoleMode(IntPtr h, uint m);
        }'
}

try {
    $debugPort = if ($env:AMSHA_DEBUG_PORT) { $env:AMSHA_DEBUG_PORT } else { '5005' }

    $mvnw    = if ($IsWindows) { '..\mvnw.cmd' } else { '../mvnw' }
    $jvmArgs = "--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

    Write-Host "[dev:debug] Starting with JDWP debug on port $debugPort"
    Write-Host "[dev:debug] Attach:  jdb -connect com.sun.jdi.SocketAttach:hostname=localhost,port=$debugPort"
    Write-Host ""

    & $mvnw quarkus:dev `
        '--no-transfer-progress' `
        "-Djvm.args=$jvmArgs" `
        '-Dquarkus.args=serve' `
        '-Dquarkus.profile=dev' `
        '-Damsha.rag.vec-extension-path=../lib/vec0' `
        '-Damsha.data-dir=../data' `
        "-Ddebug=$debugPort"
    $script:exitCode = $LASTEXITCODE

} finally {
    Get-Process -Name java -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -eq '' } |
        Stop-Process -Force -ErrorAction SilentlyContinue

    if ($IsWindows) {
        $handle = [WinCon]::GetStdHandle(-10)
        [WinCon]::SetConsoleMode($handle, 0x0007) | Out-Null
    }

    [Console]::TreatControlCAsInput = $false
    $host.UI.RawUI.FlushInputBuffer()

    Write-Host '[dev:debug] Terminal restored. Press Enter if cursor is missing.'
    # Ctrl+C → exit 130. Don't let mise report "ERROR task failed".
    if ($script:exitCode -eq 130 -or $script:exitCode -eq 143) { exit 0 }
}
