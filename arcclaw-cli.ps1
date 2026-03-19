# arcclaw-cli.ps1 - Silent CLI Wrapper for ArcClaw

# 1. Force PowerShell to use UTF-8 for this session
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8

$jvmArgs = @(
    "-Dquarkus.profile=cli",
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-Dsun.misc.unsafe.memory.access=allow",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED"
)

# Auto-inject telegram enabled for attach mode
if ($args[0] -eq "attach") {
    $jvmArgs += "-Darcclaw.telegram.enabled=true"
}

# Show help if no args provided
if ($args.Count -eq 0) {
    Write-Host ""
    Write-Host "    ___                ________"
    Write-Host "   /   |  __________  / ____/ /___ __      __"
    Write-Host "  / /| | / ___/ ___/ / /   / / __ \`/ | /| / /"
    Write-Host " / ___ |/ /  / /__  / /___/ / /_/ /| |/ |/ /"
    Write-Host "/_/  |_/_/   \___/  \____/_/\__,_/ |__/|__/"
    Write-Host ""
    Write-Host "  ArcClaw Agent Kernel - Usage"
    Write-Host ""
    Write-Host "  COMMAND                        DESCRIPTION"
    Write-Host "  -----------------------------------------------------"
    Write-Host "  arcclaw-cli.ps1 chat           Start interactive REPL"
    Write-Host "  arcclaw-cli.ps1 ask `"goal`"     One-shot task and exit"
    Write-Host "  arcclaw-cli.ps1 serve          Start REST daemon"
    Write-Host "  arcclaw-cli.ps1 attach         Start Telegram daemon"
    Write-Host ""
    Write-Host "  OPTIONS"
    Write-Host "  -----------------------------------------------------"
    Write-Host "  chat --role coordinator        Override agent role"
    Write-Host "  ask `"goal`" --role researcher   One-shot with role"
    Write-Host ""
    exit 0
}

java @jvmArgs -jar arcclaw-app/target/quarkus-app/quarkus-run.jar @args
