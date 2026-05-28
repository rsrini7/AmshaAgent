#!/usr/bin/env pwsh
#MISE description = 'Dev mode - one-shot task. Set $env:GOAL="your goal" then: mise run dev:ask'
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

$goal = if ($env:GOAL) { $env:GOAL } else { '' }
if (-not $goal) {
    Write-Host 'Usage:  $env:GOAL="Your goal here"; mise run dev:ask'
    Write-Host 'Roles:  coordinator - researcher - architect - synthesizer - debugger'
    Write-Host '        $env:ROLE="researcher"; $env:GOAL="..."; mise run dev:ask'
    exit 1
}

$envCopied = $false
try {
    if (-not (Test-Path '.env') -and (Test-Path '../.env')) {
        Copy-Item -Path '../.env' -Destination '.env'
        $envCopied = $true
        Write-Host '[dev:ask] Copied ../.env -> arcclaw-app/.env for this session'
    }

    $qargs   = "ask $goal" + $(if ($env:ROLE) { " --role $($env:ROLE)" } else { '' })
    $mvnw    = if ($IsWindows) { '..\mvnw.cmd' } else { '../mvnw' }
    $jvmArgs = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

    & $mvnw quarkus:dev `
        '--no-transfer-progress' `
        "-Djvm.args=$jvmArgs" `
        "-Dquarkus.args=$qargs" `
        '-Dquarkus.profile=dev' `
        '-Darcclaw.rag.vec-extension-path=../lib/vec0' `
        '-Darcclaw.data-dir=../data' `
        '-Ddebug=false'
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

    if ($envCopied) {
        Remove-Item -Path '.env' -Force -ErrorAction SilentlyContinue
        Write-Host '[dev:ask] Cleaned up temporary arcclaw-app/.env'
    }

    Write-Host '[dev:ask] Terminal restored. Press Enter if cursor is missing.'
    if ($script:exitCode -eq 130 -or $script:exitCode -eq 143) { exit 0 }
}
