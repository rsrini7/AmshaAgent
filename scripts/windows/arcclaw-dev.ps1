# arcclaw-dev.ps1 - ArcClaw Quarkus Dev Mode
# Relocated to scripts/windows/ — use `mise run dev` on macOS/Linux/WSL2.
# Mise equivalents: mise run dev | dev:chat | dev:attach | dev:ask

[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8

$mode = if ($args.Count -gt 0) { $args[0] } else { "serve" }

$devArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-Dsun.misc.unsafe.memory.access=allow",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    "-Dquarkus.args=$mode",
    "-Dquarkus.profile=dev",
    "-Darcclaw.rag.vec-extension-path=../lib/vec0",
    "-Darcclaw.data-dir=../data"
)

if ($mode -eq "attach") {
    $devArgs += "-Darcclaw.telegram.enabled=true"
}

.\mvnw.cmd quarkus:dev -pl arcclaw-app -am @devArgs
