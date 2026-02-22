# arcclaw-cli.ps1 - Silent CLI Wrapper for ArcClaw

# 1. Force PowerShell to use UTF-8 for this session
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8

$env:QUARKUS_PROFILE="cli"

$choice = Read-Host "Rebuild/Package application before running? (y/N)"
if ($choice -eq 'y' -or $choice -eq 'Y') {
    Write-Host "[*] Packaging application..." -ForegroundColor Cyan
    .\mvnw.cmd clean package -DskipTests -pl arcclaw-app -am
}

# 2. Force the JVM to output UTF-8 using standard and modern flags
java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" "-Dsun.stderr.encoding=UTF-8" --enable-native-access=ALL-UNNAMED -jar arcclaw-app/target/quarkus-app/quarkus-run.jar $args