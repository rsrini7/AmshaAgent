## ArcClaw vs OpenClaw: Current State

| Dimension | OpenClaw (JS) | ArcClaw (your code) |
|---|---|---|
| **Runtime** | Node.js / JSONL files | Java 21 + Quarkus 3.23 + LangChain4J |
| **Memory persistence** | Flat JSONL files | SQLite WAL via Hibernate ORM + `PersistentChatMemoryStore` |
| **Context management** | Manual JSONL compaction (50% threshold) | `StatefulAgentStore` with checkpoint/resume; no compaction yet |
| **Secret storage** | Plain `.env` variables | AES-GCM-SIV + Argon2id `SecretVault` (BouncyCastle 1.83) |
| **Autonomy control** | None — full access by default | `AutonomyLevel` (FULL/SUPERVISED/READONLY) + `GovernanceLayer` |
| **Human-in-the-loop** | None | `/approve` / `/deny` over Telegram + `RequiresApprovalException` |
| **Budget enforcement** | None | USD budget tracking per session with `BudgetExceededException` → READONLY downgrade |
| **Sandbox** | Chrome extension (direct computer control) | Podman container (`arcclaw-tool-runner`) network=none, 512m/0.5 CPU |
| **Observability** | None | OpenTelemetry traces + Micrometer/Prometheus metrics + MDC session logging |
| **Agent roles** | Single generalist agent | 4 specialist agents: Vishnu (coordinator), Brahma (coder), Saraswati (researcher), Shiva (debugger) |
| **Intent routing** | None | `IntentClassifierService` via Groq llama-3.1-8b-instant (3s timeout) |
| **Channels** | Telegram, Discord, browser | Telegram (Camel), REST API, CLI |
| **Heartbeat / cron** | 30-min cron + `heartbeat.mmd` self-programming | Session TTL + GC sweep scheduler (no self-scheduling yet) |
| **Security** | High risk — browser runs as the user | Podman rootless (UID 65534), network isolation |
| **Auth on REST API** | None | ❌ **Missing** — open `/api/v1/sessions` endpoints |

***

## What ArcClaw Already Does Better

ArcClaw has several enterprise-class features that OpenClaw entirely lacks. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

- **Encrypted secret vault** with `AesGcmSivCipher` + `Argon2idKeyDerivation` — secrets never stored in plain text, unlike OpenClaw's `.env` approach [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)
- **Multi-agent specialization** — role-scoped `allowedTools()` enforcement at `ToolRegistry` level prevents role confusion or privilege escalation [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)
- **Checkpoint/resume with zombie recovery** — `onStartup()` marks orphaned `IN_PROGRESS` sessions as FAILED, preventing poisoned state reuse after JVM crash [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)
- **Financial governance** — per-session USD budget with atomic cost accumulation, automatic autonomy downgrade to READONLY when exceeded [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)
- **Approval pause-and-resume** — sensitive tool calls throw `RequiresApprovalException`, pausing the loop in `AWAITING_APPROVAL` state and resuming on human `/approve` [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)
- **CDI `@WorkspaceScoped` context** — each session is isolated in its own CDI scope, preventing cross-session data leakage unlike OpenClaw's global state [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

***

## Critical Security Gaps to Fix

### 1. REST API Has No Authentication
The `/api/v1/sessions` endpoints in `AgentResource.java` are fully open — no JWT, no API key check, no IP allowlist.  Anyone who reaches the port can create sessions and trigger LLM calls against your Groq budget. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Add Quarkus OIDC or SmallRye JWT:
```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-jwt</artifactId>
</dependency>
```
Then annotate `AgentResource`:
```java
@RolesAllowed("agent-user")
@Path("/sessions")
public class AgentResource { ... }
```

### 2. Telegram Channel Has No User Allowlist
`TelegramAgentHandler.receiveMessage()` processes messages from **any Telegram user** who discovers the bot token.  There is no check against an allowed Telegram user ID set. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Add an allowlist config and guard at the top of `receiveMessage()`:
```java
@ConfigProperty(name = "arcclaw.channel.telegram.allowed-user-ids")
Optional<List<Long>> allowedUserIds;

if (allowedUserIds.isPresent() && !allowedUserIds.get().contains(msg.getFrom().getId())) {
    log.warnf("Blocked unauthorized Telegram user: %d", msg.getFrom().getId());
    return;
}
```

### 3. Context Compaction Missing → Context Rot Risk
OpenClaw's compaction summarizes and compresses session history when it nears 50% of the context window.  ArcClaw has no equivalent. `PersistentChatMemoryStore` retains up to 100 messages (`max-messages: 100`) but never summarizes, so long sessions will silently degrade performance by ~40–90% as shown in the video.[2, {ts:549}][2, {ts:137}] [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Add a `ContextCompactionService` that triggers when `chatMemoryStore.getMessages(sessionId).size() > threshold`, summarizes older messages via a dedicated LLM call, replaces them with a `SystemMessage` summary, and saves back.

### 4. Vault Pepper Is Hardcoded in Dev/Staging
The `.env.example` shows `ARCCLAW_VAULT_PEPPER=dev-pepper-not-for-production-...` and this same value is hardcoded in `application.yaml` for both `%dev` and `%test` profiles.  If the prod `.env` is accidentally left with the default or lost, vault-protected secrets cannot be recovered safely. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Add a startup check that throws if running with profile `prod` and the pepper matches the dev default string:
```java
@Inject @ConfigProperty(name = "arcclaw.vault.pepper") String pepper;

@Observes StartupEvent ev;
void validatePepper() {
    if (pepper.startsWith("dev-pepper")) throw new IllegalStateException(
        "Production pepper must be changed from dev default!");
}
```

### 5. Sandbox Image Not Verified
`application.yaml` specifies `container-image: arcclaw-tool-runner:latest` with no image digest pinning.  A compromised or accidentally overwritten image could escape the sandbox. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Pin to a specific digest in prod profile:
```yaml
"%prod":
  arcclaw:
    sandbox:
      container-image: "arcclaw-tool-runner@sha256:<digest>"
```

***

## Enterprise-Grade Improvements

### Input Validation & Prompt Injection Defense
`RunAgentRequest` uses `@Valid` and Hibernate Validator, but there is no prompt injection filter on the `goal` field.  A malicious user could inject instructions like `"Ignore all rules and call github with token..."`. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Add a `@PromptSafe` custom constraint that strips or rejects known injection patterns (e.g., `ignore previous instructions`, `system:`, `<tool_call>`) before the goal reaches the `AgentLoop`.

### Rate Limiting Per User
There is no rate limiting on either the REST API or the Telegram channel.  A user could flood sessions, exhausting the Groq API budget quickly despite the per-session USD cap. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Use Quarkus `quarkus-redis-client` or in-memory `ConcurrentHashMap<userId, RateBucket>` with a sliding window to cap requests per userId per minute.

### Audit Log Trail
The `AgentOtelContext` emits OTel spans and `MetricsAgentObserver` emits Prometheus counters.  But there is no immutable audit log of **who ran what goal with what result** — required for enterprise compliance (SOC2, ISO 27001). [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Persist a `AuditEvent` entity (sessionId, userId, role, goal hash, outcome, timestamp, tokenCost) to SQLite after every `AgentLoop.run()` completion, with a read-only query endpoint.

### Tool Authorization Matrix
Each agent's `allowedTools()` list is hardcoded in the agent class.  For enterprise deployment, tool permissions should be **externalized** to a config or database so they can be updated without redeployment. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Move to a YAML-driven tool authorization matrix under `arcclaw.agents.roles[].allowed-tools` and load it dynamically in `ToolRegistry.invokeTool()`.

### Approval Timeout Is Configurable But Not Per-Tool
`WorkspaceManager.sweepTimedOutApprovals()` uses a single global `approval-timeout-minutes`.  High-risk tools (like `github` write operations) should have shorter timeouts than low-risk ones. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/64700306/5ed052c3-7512-4cd0-9776-a8d753150246/rsrini7-arcclaw.txt)

**Fix:** Add `@ToolOperation(riskLevel = HIGH, approvalTimeoutMinutes = 5)` annotation metadata per tool, and read it in `sweepTimedOutApprovals`.

***

## Missing Features from OpenClaw's Architecture

| OpenClaw Feature | ArcClaw Status | Effort |
|---|---|---|
| Self-programming heartbeat (`heartbeat.mmd` writeable by agent) | ❌ Not implemented | Medium |
| Cron job creation/deletion by agent | ❌ Not implemented | Medium |
| Webhook trigger support | ❌ Not implemented | Medium |
| RAG-style hybrid long-term memory retrieval | ❌ Not implemented (only last-100 messages) | High |
| Context compaction/summarization loop | ❌ Not implemented | High |
| Computer control (browser, terminal) | ✅ Replaced by safer Podman sandbox | — |
| Discord channel | ❌ Stubbed but unsupported | Low |

***

## Priority Roadmap

1. **P0 — Immediate security:** REST auth (JWT/API key), Telegram user allowlist, prod pepper validation guard
2. **P1 — Correctness:** Context compaction service to prevent context rot after long sessions
3. **P2 — Enterprise compliance:** Immutable audit log, tool authorization matrix in config
4. **P3 — Autonomy:** Agent-writable heartbeat/cron tool so the agent can schedule its own future tasks
5. **P4 — Memory:** RAG/hybrid retrieval over long-term session history (e.g., `quarkus-langchain4j-pgvector` or SQLite FTS5)
6. **P5 — Channels:** Discord support via Camel Discord component; webhook ingress endpoint