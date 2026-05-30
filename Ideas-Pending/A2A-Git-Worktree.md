./mvnw help:system -pl arcclaw-mcp

### Phase 63 - A2A Federation (Capstone)

**Async webhook/callback architecture - no SSE holds:**

Instance A → Instance B flow:
1. `POST /a2a/tasks/send` - payload: `{goal, context, approvedTools[], callbackUrl, correlationId}`
2. Instance B creates local session + `AgentCheckpoint` in its own SQLite
3. If HITL needed: Instance B saves `AWAITING_APPROVAL` checkpoint, POSTs `{type: "ActionRequired", correlationId, toolName, params}` to `callbackUrl` on Instance A, returns `202 Accepted`
4. Instance A receives callback, looks up `A2ASuspendedCorrelationEntity` by `correlationId`, routes to human via Telegram/REST
5. Human approves on Instance A. Instance A POSTs `{type: "ApprovalGranted", correlationId}` to `POST /a2a/tasks/{taskId}/resume` on Instance B
6. Instance B resumes checkpoint, completes task, POSTs `{type: "TaskCompleted", result: SynthesisResult}` to Instance A's `callbackUrl`
7. Instance A reconstitutes state from `A2ASuspendedCorrelationEntity`, merges result, continues local `AgentLoop`

**State persistence on Instance A - `A2ASuspendedCorrelationDto`:**

JDBI DTO record per hexagonal architecture pattern. References existing `AgentCheckpoint` by FK - no state duplication.

```java
public record A2ASuspendedCorrelationDto(
    String correlationId,
    String sessionId,
    String checkpointId,  // FK to agent_checkpoint
    String remoteInstanceUrl,
    String callbackUrl,
    String status,        // PENDING / RESUMED / TIMED_OUT
    Instant expiresAt,
    Instant createdAt
) {}
```

With JDBI DAO (`A2ASuspendedCorrelationJdbiDao`) using `@RegisterConstructorMapper` and Flyway migration for the `a2a_suspended_correlation` table.

**Authentication - Ed25519 JWT via BouncyCastle + Nimbus:**

- Key generation: `Ed25519KeyPairGenerator` from BouncyCastle 1.83 (already in BOM)
- JWT wrapping: `nimbus-jose-jwt:9.37.3` - `OctetKeyPair`, `JWSHeader.Algorithm = Ed25519`, 15-minute TTL
- Key storage: private key in `SecretVault` (AES-GCM-SIV encrypted), public key in `AgentCard`
- `A2AAuthFilter` reads `Authorization: Bearer <jwt>`, validates against peer's public key from `AgentCardRegistry`

Add `com.nimbusds:nimbus-jose-jwt:9.37.3` to `arcclaw-a2a/pom.xml` with GraalVM reflection config in `native-image.properties`.

**Rule-based delegation in Dattatreya - never LLM-decided:**

`A2ADispatchHandler` algorithm:
1. Parse required `ToolCapability` set from task
2. Check local `AgentRoleEnum` permitted sets + `DynamicToolCapabilityRegistry`
3. If local capabilities are sufficient: dispatch locally (existing flow)
4. If capability gap exists: query `AgentCardRegistry` for remote instances advertising the missing capabilities
5. Select best match by capability overlap score (deterministic)
6. Delegate via async webhook. Log `A2A_TASK_DELEGATED` to WORM audit chain

LLM is never consulted for routing decisions. Hallucination of remote capabilities is structurally impossible.

**Parallel dispatch - `ManagedExecutor` + virtual threads:**

```java
@Inject ManagedExecutor executor;

// In A2ADispatchHandler for parallel multi-agent fan-out:
List<Future<AgentResult>> futures = tasks.stream()
    .map(t -> executor.submit(() -> dispatch(t)))
    .toList();
```

`StructuredTaskScope` is explicitly forbidden - still preview in JDK 25. No exceptions.

**`SecurityEventType` - new mandatory entries:**
`A2A_TASK_DELEGATED` - `isMandatory() = true` → `AsyncPersistenceEngine.submitCritical()`
`A2A_APPROVAL_PENDING` - `isMandatory() = true` → `AsyncPersistenceEngine.submitCritical()`
`A2A_RESUME_GRANTED` - `isMandatory() = true` → `AsyncPersistenceEngine.submitCritical()`

**`AgentCard` published at `/.well-known/agent.json`:**

```json
{
  "name": "arcclaw-instance-1",
  "version": "1.0.0",
  "a2aEndpoint": "https://host/a2a",
  "mcpEndpoint": "https://host/mcp",
  "capabilities": ["code-reviewer", "researcher"],
  "toolRoster": ["recall_memory_search", "archival_memory_search", "github"],
  "publicKeyJwk": { "kty": "OKP", "crv": "Ed25519", "x": "..." }
}
```

`SystemInfoResource` (Phase 58) provides `version` and instance metadata. `AgentCardRegistry` (`@ApplicationScoped`) holds known peer instances loaded from `arcclaw.a2a.peers[]` config.

**Module:** `arcclaw-a2a` (runtime only). No `arcclaw-a2a-deployment` for MVP - no build-time annotation processing required. Can be added later when `@A2ACapability` build-time scanning is introduced.

**`ReflectionConfig.java` additions:** `AgentCard.class`, `A2ASuspendedCorrelationEntity.class`, Nimbus JWT classes via `native-image.properties` in `arcclaw-a2a`.

---

## The Complete Mandate Table - All 10 Cross-Cutting Rules

| # | Phase | Mandate | Source |
|---|-------|---------|--------|
| 8 | P63 | `A2ASuspendedCorrelationDto` (JDBI record) references existing `AgentCheckpoint` by FK | Gemini Blind Spot 3 + Perplexity |
| 9 | P63 | `nimbus-jose-jwt:9.37.3` for JWT wrapping; BouncyCastle for Ed25519 key gen; `quarkus-smallrye-jwt` excluded | Perplexity Gap 7 |


---

## Final Phase Summary

| Phase | Name | Order | New Modules | Key architectural decisions |
|-------|------|-------|-------------|----------------------------|
| **P63** | A2A | 5th | `arcclaw-a2a` | Async webhook (no SSE holds); `A2ASuspendedCorrelationEntity` Hibernate-managed; Ed25519 JWT via Nimbus + BouncyCastle; rule-based delegation; `ManagedExecutor` + virtual threads |

The roadmap is now fully hardened across four AI review rounds. Every critical issue has been adjudicated with a specific, codebase-grounded fix. P60 is the cleanest starting point - shall I produce the full P60 implementation prompt in your established format (`IMPLEMENTATION REQUIREMENTS`, component-by-component, test strategy, doc updates)?

---

Using SQLite in WAL (Write-Ahead Logging) mode alongside `git worktree` is an excellent architecture for onboarding multiple users or agents in parallel, but *how* it helps depends entirely on where your SQLite database is located within that architecture. 

Here is how WAL mode and `git worktree` complement each other for a multi-user AI framework like ArcClaw.

### Scenario 1: A Central, Shared Database (Multi-Tenant Agents)
If you configure your Quarkus application to point to a single, absolute path for the SQLite database (e.g., `/var/data/arcclaw.db`), then multiple worktrees will all connect to the *same* database. In this case, **WAL mode is strictly required** for parallel user onboarding. 

SQLite's default journal mode locks the entire database file during a write, which would cause agents in different worktrees to block each other and crash due to `database is locked` exceptions. By enabling WAL mode (`PRAGMA journal_mode=WAL;`), SQLite allows multiple processes (your various Quarkus instances running in different worktrees) to read the database simultaneously while one agent writes. [oneuptime](https://oneuptime.com/blog/post/2026-03-02-how-to-set-up-sqlite-with-wal-mode-on-ubuntu/view)
- **The Benefit:** Multiple users can query agent states, retrieve LLM chat histories, or fetch metadata concurrently without blocking the agent that is currently writing to the database. [sqlite](https://sqlite.org/wal.html)
- **The Catch:** SQLite still only allows **one concurrent writer** at a time. If hundreds of agents try to write states at the exact same millisecond, connection pools (like Quarkus Agroal) will need careful timeout configurations to queue those writes properly. [oldmoe](https://oldmoe.blog/2024/07/08/the-write-stuff-concurrent-write-transactions-in-sqlite/)

### Scenario 2: Isolated Databases (One DB per Worktree)
If your SQLite database is stored locally *inside* the project directory (e.g., `./data/local.db`), then every `git worktree` automatically gets its own distinct database file. This is the ultimate sandbox for onboarding users.

In this scenario, `git worktree` provides physical isolation, so users/agents never compete for the same database locks. However, **WAL mode is still highly beneficial here for internal concurrency**. Because ArcClaw uses LangChain4j and likely processes asynchronous reactive events (like streaming LLM tokens, Telegram bot webhooks, or scheduling cron tasks), a single agent's Quarkus instance will have multiple internal threads trying to read and write to its local database. WAL mode ensures these internal application threads don't block each other during heavy AI I/O operations. [sqlite](https://sqlite.org/wal.html)

### Why this combination is a superpower for ArcClaw:
1. **Zero-Friction Onboarding:** When a new user connects to your Telegram bot, ArcClaw can execute `git worktree add ../user-workspace-<id>` and spin up a dedicated Quarkus dev server on a new port. 
2. **Crash Resilience:** If an experimental LLM prompt corrupts the database state or introduces a fatal loop, only that specific user's SQLite file and worktree are affected.
3. **High Read Throughput:** Because WAL mode writes to a separate `-wal` file rather than modifying the main `.db` file directly, read performance is significantly faster. This is crucial for multi-agent workflows where agents constantly need to read previous context and task states before taking action. [oneuptime](https://oneuptime.com/blog/post/2026-03-02-how-to-set-up-sqlite-with-wal-mode-on-ubuntu/view)

Are your users sharing a single ArcClaw database to collaborate on the same tasks, or does each user get their own completely isolated agent and database?

---

This is a fantastic architectural direction. The combination of **`git worktree` + SQLite per user + Specialized LLMs + A2A** creates a true "share-nothing" swarm architecture. It prevents the system from collapsing under the weight of shared database locks or noisy neighbor problems, while avoiding heavy infrastructure like PostgreSQL or message brokers. 

Here is a proposed design for the "ArcClaw Sandbox Swarm," followed by a few critical questions to help refine it.

### The ArcClaw Sandbox Swarm Design

#### 1. The Gateway (The Only Shared Component)
You need a single, lightweight entry point (your main ArcClaw Quarkus process) connected to your Telegram Bot. Its only job is routing.
* When User A sends a message, the Gateway checks a local map for `UserID -> Local Port`.
* If User A doesn't have an active session, the Gateway executes `git worktree add ../user-workspaces/{userId}`.
* It dynamically spins up a new Quarkus dev process for that specific worktree on a free port (e.g., `localhost:8001`) and forwards the Telegram payload to it.

#### 2. The User Sandbox (Self-Contained Swarm)
Inside `../user-workspaces/{userId}`, a fully isolated Quarkus instance runs.
* **Storage:** It relies on a local `jdbc:sqlite:arcclaw.db` in that folder (with WAL mode enabled) to store chat history, LangGraph4j checkpoints, and approval states. [quarkus](https://quarkus.io/guides/datasource)
* **No multi-tenancy logic needed:** Because the process only sees its own directory, you completely eliminate complex database multi-tenancy logic (like tenant discriminators or row-level security). [the-main-thread](https://www.the-main-thread.com/p/quarkus-multi-tenant-todo-java-hibernate)

#### 3. Agent Specialization (The LLM Routing)
Within this user process, your swarm lives. You can define distinct `@AiService` beans in LangChain4j, injecting specialized LLMs based on the agent's role:
* **Garuda (The Router):** Powered by Groq (Llama 3 8B) for 800 tokens/sec. Fast, cheap, analyzes the user's intent, and delegates tasks.
* **Vishnu (The Creator):** Powered by DeepSeek Coder or Claude 3.5 Sonnet. Best for generating massive amounts of Java code inside the worktree.
* **Brahaspati (The Reviewer):** Powered by GPT-4o. Strict system prompt focused on security, logic checks, and approving Vishnu's code before merging.

#### 4. The A2A Implementation (Option B in Practice)
To implement your preferred Option B (network/event-based communication) while keeping things self-contained, you have two excellent paths in Quarkus:
* **Internal EventBus (Recommended for Single-JVM Swarms):** Quarkus ships with the Vert.x EventBus. Instead of heavy REST calls, Vishnu can publish a task completion event `vertx.eventBus().publish("agent.brahaspati.review", taskPayload)`. Brahaspati listens on this address, processes the review in its own LangGraph, and replies. This mimics distributed A2A with zero network overhead. [quad](https://www.quad.team/blog/messaging-with-quarkus-and-vertx)
* **REST/HTTP (For True Distributed Swarms):** If you physically split agents into separate processes later, they can use standard Quarkus REST clients to trigger each other's LangGraph4j workflows via exposed `@Path("/api/agents/brahaspati")` endpoints.

***

### Questions to Refine the Architecture

While this design is clean, the "Git Worktree + Isolated DB" model introduces some lifecycle and resource challenges. Let's iron them out:

**1. Resource Footprint:** 
Spinning up a full Quarkus JVM per user via `git worktree` provides perfect isolation, but a standard JVM uses 150-300MB of RAM. If 10 users are active, that's 3GB of RAM. 
* *Are you planning to run these user-specific workspaces as JVMs, or will you compile ArcClaw down to GraalVM native binaries (which use only ~30MB of RAM and start in 50ms) to make this swarm scale?*

**2. The Shared Codebase Problem:** 
If agents are operating inside a `git worktree` modifying the ArcClaw code, what happens when Vishnu successfully writes an awesome new feature for User A? 
* *Do the agents push that branch to a central Git remote to create a Pull Request, or do they merge it directly into the local `main` branch that other worktrees are checked out from?*

**3. Lifecycle Management:** 
Since everything is self-contained without a central database, what is the cleanup strategy? 
* *When a user stops talking to the Telegram bot for 24 hours, does the Gateway kill their Quarkus process and delete the worktree, or do you keep their `arcclaw.db` permanently archived somewhere?*

---

Great question - and the answer is **not quite N×M in physical resources, but yes in interaction space**. Here is the precise breakdown:

## The Physical Resource Dimension (O(N), not O(N×M))

The key insight is that **M agents within a user's sandbox are LOGICAL**, not physical processes. Each user's `git worktree` spawns exactly **one Quarkus JVM**. Inside it, your M agents (Vishnu, Brahaspati, Garuda, etc.) are simply CDI beans or LangGraph4j `StateGraph` nodes sharing the same heap and Vert.x EventBus. This means: [docs.swarms](https://docs.swarms.world/en/latest/swarms/concept/swarm_architectures/)

| Dimension | Count | Type |
|---|---|---|
| Git worktrees | N | Physical directories |
| Quarkus JVM processes | N | Physical processes |
| SQLite databases | N | Physical files |
| Agents per JVM | M | Logical beans/threads |
| LLM API calls | N × M | Actual external calls |

So for 10 users with 5 agents each, you have **10 JVMs**, not 50. The M dimension stays entirely in-process and is essentially free until an agent makes an LLM call. [techcommunity.microsoft](https://techcommunity.microsoft.com/blog/appsonazureblog/the-swarm-diaries-what-happens-when-you-let-ai-agents-loose-on-a-codebase/4501393)

## The Real N×M Problem - LLM Rate Limits

Where N×M *does* hurt is **LLM provider rate limits**. If N=20 users are each running M=5 agents concurrently, you can have 100 simultaneous calls to providers like Groq or OpenAI. This is ArcClaw's actual scaling constraint. The mitigation in your design:

- **Distribute M across different providers** by agent specialty - Garuda hits Groq, Vishnu hits DeepSeek, Brahaspati hits OpenAI. This spreads the N×M load across M separate rate limit buckets instead of hammering one provider.
- **LangGraph4j checkpointing to SQLite** naturally throttles agents - a paused approval workflow doesn't consume an active LLM connection.

## The Interaction Topology

For A2A communication, the interactions are O(N×M) in **message flow** but hierarchical, not full-mesh. A full mesh would be catastrophic - that's O((N×M)²) message paths. Instead, use a **hierarchical + hub-and-spoke hybrid**: [arxiv](https://arxiv.org/html/2603.19431v1)

```
Gateway (1 process)
    │
    ├── UserA-Worktree (1 JVM)
    │       └── Garuda → [EventBus] → Vishnu → [EventBus] → Brahaspati
    │
    ├── UserB-Worktree (1 JVM)
    │       └── Garuda → [EventBus] → Vishnu → [EventBus] → Brahaspati
    │
    └── (Cross-user A2A via HTTP only when explicitly needed)
```

Within a user's JVM, agents talk via Vert.x EventBus (zero-cost, in-process). Cross-user communication (rare in a personal assistant scenario) goes through the Gateway via HTTP. This keeps the hot path as O(M) per user, not O(N×M) globally. [quad](https://www.quad.team/blog/messaging-with-quarkus-and-vertx)

## The Practical Scaling Rule for ArcClaw

The self-contained "no-server" design holds well up to roughly **N=30–50 concurrent users** on a modest 8GB RAM machine (assuming GraalVM native builds at ~50MB each). Beyond that, the bottleneck shifts from RAM to LLM provider rate limits - which is where your provider-per-agent specialization strategy becomes a genuine architectural advantage rather than just a nice-to-have.

---

## The Two Levels of ArcClaw

You have two distinct communication layers and they should **never mix their protocols**:

```
LEVEL 1: Inside One ArcClaw Instance
─────────────────────────────────────
 [ArcClaw for UserA]
   Garuda ──EventBus──► Vishnu ──EventBus──► Brahaspati
         (Personas, internal, same JVM, no A2A)

LEVEL 2: Between ArcClaw Instances (THE A2A LAYER)
───────────────────────────────────────────────────
 [ArcClaw-UserA]  ←── Google A2A JSON-RPC ──►  [ArcClaw-UserB]
 [ArcClaw-UserA]  ←── Google A2A JSON-RPC ──►  [ArcClaw-Remote]
 [ArcClaw-UserA]  ←── Google A2A JSON-RPC ──►  [LangGraph Agent]
```

Each **ArcClaw instance is itself THE agent** from the outside world's perspective. Its internal personas (Vishnu, Brahaspati, Garuda) are private implementation details - other ArcClaw instances don't know or care which persona handles the task internally. [developers.googleblog](https://developers.googleblog.com/en/a2a-a-new-era-of-agent-interoperability/)

## Why This Distinction Matters

If you exposed personas directly via A2A (e.g., a dedicated Vishnu endpoint and a dedicated Brahaspati endpoint), you'd create a **chatty, tightly coupled mesh** between instances. Every external caller would need to know ArcClaw's internal persona structure - completely defeating the purpose of encapsulation. [docs.swarms](https://docs.swarms.world/en/latest/swarms/concept/swarm_architectures/)

Instead, with ArcClaw-as-agent:
- Each instance publishes **one Agent Card** at `/.well-known/agent.json` describing what the whole ArcClaw can do (write code, review PRs, answer queries, etc.) [a2a-protocol](https://a2a-protocol.org/v0.2.5/specification/)
- Internally, the receiving ArcClaw decides which persona handles the incoming A2A task - the caller never knows
- You can completely redesign your persona routing without breaking any external A2A contract

## What This Looks Like in Practice for ArcClaw

```
ArcClaw-UserA needs a security audit it can't handle alone
    │
    ▼
Garuda reads Agent Cards of known ArcClaw instances
    │
    ▼
Finds ArcClaw-UserB specializes in security (declared in its Agent Card)
    │
    ▼
Sends: POST /a2a  {method: "tasks/send", params: {skill: "security-audit", input: code}}
    │
    ▼
ArcClaw-UserB receives it → internally routes to Brahaspati via EventBus
    │
    ▼
Returns result back to ArcClaw-UserA via A2A response
```

The calling ArcClaw-UserA never knew Brahaspati existed. [dev](https://dev.to/vishalmysore/google-a2a-protocol-integrating-ai-into-existing-java-applications-4nlp)

## The Clean Rule for ArcClaw

| What communicates | Protocol | Reason |
|---|---|---|
| Persona → Persona (within ArcClaw) | Vert.x EventBus | Same JVM, private, zero cost |
| ArcClaw → ArcClaw | Google A2A JSON-RPC | Cross-JVM, public contract |
| ArcClaw → External agent (LangGraph, CrewAI) | Google A2A JSON-RPC | Vendor-neutral interop  [galileo](https://galileo.ai/blog/google-agent2agent-a2a-protocol-guide) |

This gives ArcClaw a **clean public API surface** (one Agent Card, one A2A endpoint per instance) while keeping personas as a free internal optimization - exactly aligned with your self-contained, no-heavy-dependency philosophy.

---