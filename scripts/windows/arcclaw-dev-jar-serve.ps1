# arcclaw-dev-jar-serve.ps1 - Run packaged jar in dev profile (serve mode)
# Relocated to scripts/windows/ — use `mise run jar:serve-dev-profile` on macOS/Linux/WSL2.

$devArgs = @(
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-Dsun.misc.unsafe.memory.access=allow",
    "-Dquarkus.profile=dev",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED"
)

java @devArgs -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
