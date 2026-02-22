# arcclaw.ps1 - Silent CLI Wrapper for ArcClaw
$env:QUARKUS_PROFILE="cli"

# Optional build step
$choice = Read-Host "Rebuild/Package application before running? (y/N)"
if ($choice -eq 'y' -or $choice -eq 'Y') {
    Write-Host "[*] Packaging application..." -ForegroundColor Cyan
    .\mvnw.cmd clean package -DskipTests -pl arcclaw-app -am
}

# Execute the JAR with the required JVM flags and pass any CLI arguments ($args)
java --enable-native-access=ALL-UNNAMED -jar arcclaw-app/target/quarkus-app/quarkus-run.jar $args

