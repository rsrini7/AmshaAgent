# ArcClaw Verification & Testing Guide

This guide details how to verify the end-to-end functionality of ArcClaw.

## Prerequisites
1. Ensure the docker-compose stack is running:
   ```bash
   docker compose up -d
   ```
2. Ensure the Quarkus application is running:
   ```bash
   mvnw quarkus:dev -pl arcclaw-app
   ```

## E2E API Workflow

### 1. Create a Session
Create a new agent session to obtain a unique Session ID.

```bash
curl -X POST http://localhost:8080/api/v1/sessions
```
*Expected Response:*
```json
{"sessionId":"<extracted-uuid-here>"}
```

*Set your Session ID as an environment variable for the rest of the commands:*
```bash
# Windows (PowerShell)
$SESSION_ID="<extracted-uuid-here>"
# Linux/macOS
export SESSION_ID="<extracted-uuid-here>"
```

### 2. Run an Agent (Asynchronous)
Execute the Vishnu coordinator agent with a specific goal. This endpoint is non-blocking.

```bash
# Windows
curl -X POST http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async `
     -H "Content-Type: application/json" `
     -d '{ "goal": "Analyze the openclaw repository on github and tell me what it does", "agentRole": "coordinator" }'

# Linux/macOS
curl -X POST http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async \
     -H "Content-Type: application/json" \
     -d '{"goal": "Analyze the openclaw repository on github and tell me what it does", "agentRole": "coordinator"}'
```
*Expected Response:* `HTTP 202 Accepted`

### 3. Poll the Session Status
Check the status of the asynchronous agent execution. Run this repeatedly to watch the agent's progress.

```bash
# Windows
curl http://localhost:8080/api/v1/sessions/$SESSION_ID/status/coordinator

# Linux/macOS
curl http://localhost:8080/api/v1/sessions/$SESSION_ID/status/coordinator
```
*Expected Response:* JSON payload containing the current `status` (e.g., `IN_PROGRESS`, `COMPLETED`) and a `steps` array detailing the agent's thoughts and actions.

## Tracing (Jaeger)

ArcClaw uses OpenTelemetry to trace requests across the API, the reasoning engine, and external tool executions.

To view traces:
1. Open the Jaeger UI in your browser at: [http://localhost:16686](http://localhost:16686)
2. In the **Service** dropdown, select `arcclaw`.
3. Click **Find Traces** to view the timeline of requests.
4. Click on an individual trace to see the span hierarchy, which will include the HTTP request, the agent loop, LLM interactions, and executed Sandbox tool operations.


## Sandbox Isolation Tier Testing (ProcessIsolationExecutor)

ArcClaw's sandbox layer degrades gracefully depending on the active Quarkus profile and host OS capabilities. To mathematically prove the sandbox is working in all scenarios, follow these three testing phases.

### Prerequisites for Sandbox Testing
1. **GitHub CLI**: `gh` must be installed on your host machine's `PATH`.
2. **Podman**: Podman Desktop must be installed (for Windows/Mac) or natively installed (Linux).
3. **Tool Runner Image**: You must build the base container image that Podman will spin up. 

💡 Special Note for Windows Users (The WSL Boundary Trap)

If you are developing on Windows and want to test **Tier 2 (Podman)**, you cannot simply run the `.jar` in PowerShell. 
1. `ProcessIsolationExecutor` checks `System.getProperty("os.name")`. If it detects Windows, it safely bypasses Linux container namespaces and falls back to host execution.
2. Therefore, **you must run the application inside WSL (e.g., Ubuntu).**

However, Podman Desktop runs in its own isolated WSL VM (`podman-machine-default`). If you open your Ubuntu terminal, commands like `podman machine list` or `podman ps` will be empty or fail because Ubuntu cannot see the Podman VM.

**The Solution: Treat WSL Ubuntu as a Native Linux Server**
Do not try to bridge Podman Desktop into Ubuntu. Instead, install Podman natively *inside* your Ubuntu WSL distribution.

**Step 1: Open your Ubuntu WSL terminal**

Follow [PODMAN SETUP WSL2](./PODMAN_SETUP_WSL2.md)

**Step 2: Build the Tool Runner Image (Inside Ubuntu)**

```bash
cat <<EOF > Dockerfile.toolrunner
FROM alpine:latest
RUN apk add --no-cache github-cli
EOF

podman build -t arcclaw-tool-runner:latest -f Dockerfile.toolrunner

```

**Step 3: Run ArcClaw (Inside Ubuntu)**
Ensure you are in the `/mnt/c/.../ArcClaw` directory (your project root mounted in WSL).

```bash
# Build the JAR
./mvnw clean package -pl arcclaw-app -am -DskipTests

# Run the production binary natively in Linux
java -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve

```

When you trigger the `gh` tool now via Telegram, the Java process will correctly identify the OS as Linux, locate the native `podman` executable, and successfully launch the rootless container isolation.

---

## 🛠️ Sandbox Troubleshooting: Why is my Strategy 'NONE'?

If you are running the application but it appears to be bypassing the sandbox (using direct `gh` on your host), check these four technical constraints.

### 1. Understanding Quarkus Profiles
The sandbox strategy is profile-aware. If you run via `mvnw quarkus:dev`, you are in the **`%dev`** profile which hardcodes `strategy: NONE` for developer velocity.

| Execution Mode | Default Profile | Expected Strategy |
| --- | --- | --- |
| `.\mvnw quarkus:dev` | **`%dev`** | `NONE` (No isolation) |
| `java -jar arcclaw-run.jar` | **`%prod`** | `PODMAN` (Strict isolation) |

**How to force Prod mode for testing:**
```powershell
# Force the production profile even when running the JAR locally
java -Dquarkus.profile=prod -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

### 2. Initialization "Freeze" & Circuit Breaker
The `ProcessIsolationExecutor` determines its capability **only once** at startup.
- **The Check**: It runs `podman --version` at boot.
- **The Result**: If Podman is not running or not on the `PATH` during those first 5 seconds, the **Circuit Breaker** trips. 
- **The Consequence**: The strategy is permanently downgraded to `NAMESPACE` or `NONE` for that JVM session. Starting Podman *after* the app has booted will not fix it; you must restart ArcClaw.

### 3. The Windows OS Bypass
ArcClaw contains a safety guard: if `os.name` contains "Windows", it knows it cannot use Linux Cgroups/Namespaces. 
- Even if your strategy is `PODMAN`, if the circuit breaker trips, the fallback to `NAMESPACE` will immediately see it is on Windows and skip to `executeDirectly()`.
- **Solution**: Use the [Native Podman Setup Guide](./PODMAN_SETUP_WSL2.md) to verify true container isolation inside WSL2.

### 4. Verification Checklist
To mathematically confirm your isolation level, check the **first 50 lines** of the console output during boot:
- ✅ `INFO: Isolation strategy: PODMAN initialized.` -> **Full Security**
- ⚠️ `WARN: Podman not available — circuit open, falling back...` -> **Degraded Security**
