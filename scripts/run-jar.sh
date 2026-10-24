#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
cd "$project_root"

if ! command -v java >/dev/null 2>&1; then
    printf '%s\n' '[Amsha Agent] JDK 25 is required. Install JDK 25 and ensure java is on PATH.' >&2
    exit 1
fi

java_spec_version=$(java -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/java.specification.version =/ { print $2; exit }')
if [ "$java_spec_version" != "25" ]; then
    printf '[Amsha Agent] JDK 25 is required; found Java version %s.\n' "${java_spec_version:-unknown}" >&2
    exit 1
fi

printf '%s\n' '[Amsha Agent] Building the packaged application. The first build can take a few minutes.'
./mvnw -B -pl app -am package -DskipTests

jar_path="$project_root/app/target/quarkus-app/quarkus-run.jar"
if [ ! -f "$jar_path" ]; then
    printf '[Amsha Agent] Build completed but the application JAR was not found: %s\n' "$jar_path" >&2
    exit 1
fi

if [ "$#" -eq 0 ]; then
    set -- chat
fi

runtime_dir="$project_root"
if [ ! -f "$runtime_dir/.env" ] && [ -f "$project_root/app/.env" ]; then
    runtime_dir="$project_root/app"
    printf '%s\n' '[Amsha Agent] Reusing app/.env from an existing Quarkus dev setup.'
fi

cd "$runtime_dir"
printf '[Amsha Agent] Starting the packaged application from %s.\n' "$runtime_dir"
exec java --enable-native-access=ALL-UNNAMED -jar "$jar_path" "$@"
