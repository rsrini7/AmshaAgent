# mvn-clean-install-skiptests.ps1 - Full build without tests
# Relocated to scripts/windows/ — use `mise run build` or `mise run build:full` on macOS/Linux/WSL2.

$env:JDK_JAVA_OPTIONS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dquarkus.otel.sdk.disabled=true"
.\mvnw.cmd clean install -pl arcclaw-core,arcclaw-channel-api,arcclaw-mcp,arcclaw-rag,arcclaw-deployment,arcclaw-app -DskipTests
