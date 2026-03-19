[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:JDK_JAVA_OPTIONS = "--add-opens=java.base/java.nio=ALL-UNNAMED " +
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED " +
    "--add-opens=java.base/java.lang=ALL-UNNAMED " +
    "--enable-native-access=ALL-UNNAMED " +
    "-Dsun.misc.unsafe.memory.access=allow " +
    "-Dquarkus.otel.sdk.disabled=true"

# Default mode is serve; override with first arg e.g. .\arcclaw-dev.ps1 chat
$mode = if ($args.Count -gt 0) { $args[0] } else { "serve" }

$devArgs = @(
    "-Dquarkus.args=$mode",
    "-Darcclaw.rag.vec-extension-path=../lib/vec0",
    "-Darcclaw.data-dir=../data"
)

# Auto-inject telegram enabled for attach mode
if ($mode -eq "attach") {
    $devArgs += "-Darcclaw.telegram.enabled=true"
}

if ($args.Count -eq 0) {
    Write-Host ""
    Write-Host "  ArcClaw Quarkus Dev Mode - Usage"
    Write-Host ""
    Write-Host "  COMMAND                          DESCRIPTION"
    Write-Host "  -------------------------------------------------------"
    Write-Host "  arcclaw-dev.ps1                  Dev mode (serve)"
    Write-Host "  arcclaw-dev.ps1 serve            Start REST daemon"
    Write-Host "  arcclaw-dev.ps1 chat             Interactive REPL"
    Write-Host "  arcclaw-dev.ps1 ask `"goal`"       One-shot task"
    Write-Host "  arcclaw-dev.ps1 attach           Telegram daemon"
    Write-Host ""
    # Fall through to serve (default)
}

.\mvnw.cmd quarkus:dev -pl arcclaw-app -am @devArgs
