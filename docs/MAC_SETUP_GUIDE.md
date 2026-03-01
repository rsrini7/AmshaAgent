brew install podman

podman machine init --memory 6144 --cpus 4 --disk-size 40

podman machine start

----


below points from brew gave..

Starting machine "podman-machine-default"

This machine is currently configured in rootless mode. If your containers
require root permissions (e.g. ports < 1024), or if you run into compatibility
issues with non-podman clients, you can switch using the following command:

	podman machine set --rootful

API forwarding listening on: /var/folders/p2/9x91kvtj38x2km_fffb62hf40000gn/T/podman/podman-machine-default-api.sock

The system helper service is not installed; the default Docker API socket
address can't be used by podman. If you would like to install it, run the following commands:

        sudo /opt/homebrew/Cellar/podman/5.8.0/bin/podman-mac-helper install
        podman machine stop; podman machine start

You can still connect Docker API clients by setting DOCKER_HOST using the
following command in your terminal session:

        export DOCKER_HOST='unix:///var/folders/p2/9x91kvtj38x2km_fffb62hf40000gn/T/podman/podman-machine-default-api.sock'

Machine "podman-machine-default" started successfully


---

mvnw clean package -pl arcclaw-app -am -DskipTests

----

/usr/libexec/java_home -V

jenv add /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

jenv global 25.0


graalvm install then do below

./mvnw clean package -Pnative -Dquarkus.native.container-build=false -DskipTests

mvn clean verify -pl arcclaw-app -am

./mvnw clean package -pl arcclaw-app -am -DskipTests

./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  -Dsun.misc.unsafe.memory.access=allow \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar \
  serve

java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED  --add-opens=java.base/java.lang=ALL-UNNAMED "-Dsun.misc.unsafe.memory.access=allow" -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve

----

podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/

---

git fetch origin
git diff origin/main...HEAD  > diff-phaseX.md


git diff --name-only --staged | xargs cat > all_staged_content.txt

git diff --name-only --staged | ForEach-Object { Get-Content $_ } | Set-Content all_staged_content.txt

---

git diff --name-only --diff-filter=AM origin/main...HEAD |
Where-Object { $_ -notmatch "\.(md|txt)$" } |
ForEach-Object {
    "===== $_ ====="
    Get-Content -Raw $_
    ""
} | Set-Content modified_files_full_content.txt