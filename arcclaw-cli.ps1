# arcclaw.ps1 - Silent CLI Wrapper for ArcClaw
$env:QUARKUS_PROFILE="cli"

# Execute the JAR with the required JVM flags and pass any CLI arguments ($args)
java --enable-native-access=ALL-UNNAMED -jar arcclaw-app/target/quarkus-app/quarkus-run.jar $args

