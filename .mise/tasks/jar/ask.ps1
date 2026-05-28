#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = 'Run packaged jar - one-shot task. Set $env:GOAL="..." then: mise run jar:ask'
$ErrorActionPreference = 'Stop'
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build-app'; exit 1 }
$goal = if ($env:GOAL) { $env:GOAL } else { '' }
if (-not $goal) {
    Write-Host 'Usage:  $env:GOAL="Your goal here"; mise run jar:ask'
    Write-Host 'Roles:  coordinator - researcher - architect - synthesizer - debugger'
    Write-Host '        $env:ROLE="researcher"; $env:GOAL="..."; mise run jar:ask'
    exit 1
}
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-Dquarkus.profile=cli',
    '-Darcclaw.rag.vec-extension-path=./lib/vec0',
    '-Darcclaw.data-dir=./data'
)
$cmdArgs = @('ask', $goal)
if ($env:ROLE) { $cmdArgs += '--role', $env:ROLE }
& java @jvmArgs -jar $jar @cmdArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
