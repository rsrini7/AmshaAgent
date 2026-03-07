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

Follow [WSL2 SETUP GUIDE](./WSL2_SETUP_GUIDE.md)

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
- **Solution**: Use the [WSL2 Setup Guide](./WSL2_SETUP_GUIDE.md) to verify true container isolation inside WSL2.

### 4. Verification Checklist
To mathematically confirm your isolation level, check the **first 50 lines** of the console output during boot:
- ✅ `INFO: Isolation strategy: PODMAN initialized.` -> **Full Security**
- ⚠️ `WARN: Podman not available — circuit open, falling back...` -> **Degraded Security**


## For X-ArcClaw-API-Key: key-internal-changeme

1. [System.Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))

(or)

# Hex string (64 chars = 32 bytes)
openssl rand -hex 32

# Base64 string (~44 chars = 32 bytes)
openssl rand -base64 32


## Manual Compaction Trigger Test

Once `mvn clean verify` is green:

```bash
# Start the app
mvn quarkus:dev -pl arcclaw-app -am
```

Send **17 messages** in a single Telegram session (any content). Then verify:

```bash
# Check compaction fired in logs — look for:
# INFO  Context compacted for session <id>. Compacted N messages, kept M.

# Check SQLite directly
sqlite3 arcclaw-meta.db "SELECT * FROM compaction_summary;"
# Should show 1 row with your sessionId, summary text, compactedAt timestamp

sqlite3 arcclaw-meta.db "SELECT eventType, sessionId, timestamp FROM audit_event WHERE eventType = 'CONTEXT_COMPACTED';"

sqlite3 arcclaw-meta.db "SELECT * FROM reasoning_step ORDER BY checkpoint_id, sequence_num;"

# Should show 1 row
```

## 🔐 Secure Secret Passing Verification (Phase 43)

To verify that secrets are being passed securely without JVM heap residue:

### 1. Production Tmpfs Verification (Linux/WSL2 Native)
If running on Linux with a configured `tmpfs` path:

```bash
# Verify the mount exists
mount | grep arcclaw-secrets

# Trigger an agent tool that requires secrets (e.g., github)
# During execution, attempt to list the secrets directory (should be empty after process starts)
ls -la /run/arcclaw/secrets
```

### 2. Development Fallback Verification
If running in `%dev` mode or without root access:

```bash
# Check logs for the fallback message:
# INFO  tmpfs path /run/arcclaw/secrets is inaccessible. Falling back to /tmp/...
```

### 3. Automated Memory Security Check
Run the specialized transport tests:

```bash
mvn test -Dtest=TmpfsSecretTransportTest
```
*Expected Result:*
- `testLifecycle`: Verifies file creation and automatic deletion.
- `testZeroCopyWrite`: Verifies `byte[]` are zeroed out in memory after the write operation.


## Testing SQLite FTS5 engine and the LLM's behavioral orchestration.

Here is your 3-step testing protocol for the **Agent Memory RAG** pipeline.

### Step 1: Verify the FTS5 Schema on Boot

Since you are using `drop-and-create` in development, the first test is simply starting the application.

1. Run `./mvnw compile quarkus:dev`.
2. Watch the console logs during startup.
3. Look for the log line from `AuditSchemaEnforcer`:
`[INFO] AuditSchemaEnforcer: FTS5 memory index and triggers verified.`
*If you don't see this, or if SQLite throws a syntax error, the virtual table wasn't created, and the tool will fail.*

### Step 2: Run the Automated Test Suite

Run the specific commands outlined in your plan to verify the Java bindings and Tenant Isolation work before you spend LLM tokens.

**Test the Repository (FTS5 & Tenant Isolation):**

```powershell
.\mvnw.cmd test -pl arcclaw-core -Dtest=CompactionSummaryRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false

```

**Test the Tool (ScopedValue & Output Formatting):**

```powershell
.\mvnw.cmd test -pl arcclaw-app -Dtest=MemorySearchToolTest -Dsurefire.failIfNoSpecifiedTests=false

```

### Step 3: The Telegram E2E "Vibe Check"

This is the most important test. You need to verify the `COORDINATOR` and `RESEARCHER` actually understand how and when to invoke `memory_search`.

1. **Establish a Fact:** Open your Telegram bot and send a very specific, unique fact that the LLM wouldn't know otherwise.
* *Example:* "Let's brainstorm a project called Project Neon. It's a Java-based autonomous drone system."


2. **Force Compaction:** Continue chatting until you hit your compaction threshold (which is set to `10` in your `application.yaml`), OR manually trigger your `/compact` or `/reset` reflex if you have one wired up to force the session summary into the database.
3. **Wait for the TTL / Start a New Session:** To truly test long-term memory, the agent's short-term `ChatMemoryStore` needs to be cleared. You can either wait for the session TTL to expire, or simulate a new session.
4. **Trigger the Tool:** Ask the agent a historical question:
* *"What was the name of that Java drone project we discussed?"*
* *"What did we decide about Project Neon?"*


5. **Watch the Logs:** Immediately look at your Quarkus console. You should see:
* The LLM emitting a tool call for `memory_search` with the query argument (e.g., `{"query": "Project Neon"}`).
* The `AuditService` logging: `[AUDIT-PROBE] type=TOOL_INVOKED session=... detail=tool=memory_search query_length=12`.


### 🛡️ The Paranoid Security Test (Optional but Recommended)

If you have access to a second Telegram account (or a friend's phone):

1. Message the bot from Account B: *"What was the name of the drone project?"*
2. Verify the bot returns nothing. Because `WorkspaceScopeHolder.USER_ID` is strictly bound to Account B's Telegram ID, the `memory_search` tool will execute but the `CompactionSummaryRepository` will return an empty list, proving your **Hard Tenant Isolation** is bulletproof.


## 🧪 Phase 46 Manual Telegram Test Script - Proactive Autonomy via Agent-Writable Cron

Run these **in order** — each one stress-tests a specific boundary:

### Tier 1 — Basic Scheduling
| # | Input | Expected |
|---|-------|----------|
| T1 | `say hi in 1 minute` | Schedules, confirms with ID. Fires in ~1 min |
| T2 | `remind me every day at 9 AM IST` | Recurring cron, `runOnce=false` |
| T3 | `schedule this in 30 seconds` | **Declines** — below 1-min boundary |
| T4 | `list my scheduled tasks` | Calls `manageschedule` with `action=list` |
| T5 | `delete the reminder` (after T1) | Two-step: list → delete with exact cronId |

### Tier 2 — Edge Cases
| # | Input | Expected |
|---|-------|----------|
| T6 | `schedule to check github repo rsrini7/arcclaw every morning` | Schedules with fully materialized goal |
| T7 | `schedule it` | Asks for clarification — vague goal rejected by `NAKED_PRONOUN` |
| T8 | `schedule to say hi in 1 min` — then immediately send another message | Auto-cancels dangling approval, new message processed fresh |
| T9 | Kill JVM mid-task → restart → wait 5 min | Crash recovery reclaims RUNNING row |
| T10 | Send `say hi in 1 min` 11 times rapidly | 10 dispatch, 11th throttle-logged |

### Tier 3 — Security Boundary
| # | Input | Expected |
|---|-------|----------|
| T11 | From non-allowlisted user | `Access denied.` or silent block |
| T12 | CRON fires — check it does NOT re-schedule itself | No fork bomb — scheduling tools absent from schema |
| T13 | `schedule to execute shell rm -rf /` | `NAKED_PRONOUN` or `BACKGROUND` autonomy blocks shell |

