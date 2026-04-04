./mvnw help:system -pl arcclaw-mcp

### Phase 63 — A2A Federation (Capstone)

**Async webhook/callback architecture — no SSE holds:**

Instance A → Instance B flow:
1. `POST /a2a/tasks/send` — payload: `{goal, context, approvedTools[], callbackUrl, correlationId}`
2. Instance B creates local session + `AgentCheckpoint` in its own SQLite
3. If HITL needed: Instance B saves `AWAITING_APPROVAL` checkpoint, POSTs `{type: "ActionRequired", correlationId, toolName, params}` to `callbackUrl` on Instance A, returns `202 Accepted`
4. Instance A receives callback, looks up `A2ASuspendedCorrelationEntity` by `correlationId`, routes to human via Telegram/REST
5. Human approves on Instance A. Instance A POSTs `{type: "ApprovalGranted", correlationId}` to `POST /a2a/tasks/{taskId}/resume` on Instance B
6. Instance B resumes checkpoint, completes task, POSTs `{type: "TaskCompleted", result: SynthesisResult}` to Instance A's `callbackUrl`
7. Instance A reconstitutes state from `A2ASuspendedCorrelationEntity`, merges result, continues local `AgentLoop`

**State persistence on Instance A — `A2ASuspendedCorrelationEntity`:**

Standard Hibernate `@Entity` per Architecture Nuance #11. References existing `AgentCheckpoint` by FK — no state duplication.

```java
@Entity
@Table(name = "a2a_suspended_correlation")
public class A2ASuspendedCorrelationEntity {
    @Id String correlationId;
    String sessionId;
    String checkpointId;  // FK to agent_checkpoint
    String remoteInstanceUrl;
    String callbackUrl;
    String status;        // PENDING / RESUMED / TIMED_OUT
    @Convert(converter = InstantToTextConverter.class) Instant expiresAt;
    @Convert(converter = InstantToTextConverter.class) Instant createdAt;
}
```

**Authentication — Ed25519 JWT via BouncyCastle + Nimbus:**

- Key generation: `Ed25519KeyPairGenerator` from BouncyCastle 1.83 (already in BOM)
- JWT wrapping: `nimbus-jose-jwt:9.37.3` — `OctetKeyPair`, `JWSHeader.Algorithm = Ed25519`, 15-minute TTL
- Key storage: private key in `SecretVault` (AES-GCM-SIV encrypted), public key in `AgentCard`
- `A2AAuthFilter` reads `Authorization: Bearer <jwt>`, validates against peer's public key from `AgentCardRegistry`

Add `com.nimbusds:nimbus-jose-jwt:9.37.3` to `arcclaw-a2a/pom.xml` with GraalVM reflection config in `native-image.properties`.

**Rule-based delegation in Dattatreya — never LLM-decided:**

`A2ADispatchHandler` algorithm:
1. Parse required `ToolCapability` set from task
2. Check local `AgentRoleEnum` permitted sets + `DynamicToolCapabilityRegistry`
3. If local capabilities are sufficient: dispatch locally (existing flow)
4. If capability gap exists: query `AgentCardRegistry` for remote instances advertising the missing capabilities
5. Select best match by capability overlap score (deterministic)
6. Delegate via async webhook. Log `A2A_TASK_DELEGATED` to WORM audit chain

LLM is never consulted for routing decisions. Hallucination of remote capabilities is structurally impossible.

**Parallel dispatch — `ManagedExecutor` + virtual threads:**

```java
@Inject ManagedExecutor executor;

// In A2ADispatchHandler for parallel multi-agent fan-out:
List<Future<AgentResult>> futures = tasks.stream()
    .map(t -> executor.submit(() -> dispatch(t)))
    .toList();
```

`StructuredTaskScope` is explicitly forbidden — still preview in JDK 25. No exceptions.

**`SecurityEventType` — new mandatory entries:**
`A2A_TASK_DELEGATED` — `isMandatory() = true` → `AsyncPersistenceEngine.submitCritical()`
`A2A_APPROVAL_PENDING` — `isMandatory() = true` → `AsyncPersistenceEngine.submitCritical()`
`A2A_RESUME_GRANTED` — `isMandatory() = true` → `AsyncPersistenceEngine.submitCritical()`

**`AgentCard` published at `/.well-known/agent.json`:**

```json
{
  "name": "arcclaw-instance-1",
  "version": "1.0.0",
  "a2aEndpoint": "https://host/a2a",
  "mcpEndpoint": "https://host/mcp",
  "capabilities": ["code-reviewer", "researcher"],
  "toolRoster": ["memory_search", "github"],
  "publicKeyJwk": { "kty": "OKP", "crv": "Ed25519", "x": "..." }
}
```

`SystemInfoResource` (Phase 58) provides `version` and instance metadata. `AgentCardRegistry` (`@ApplicationScoped`) holds known peer instances loaded from `arcclaw.a2a.peers[]` config.

**Module:** `arcclaw-a2a` (runtime only). No `arcclaw-a2a-deployment` for MVP — no build-time annotation processing required. Can be added later when `@A2ACapability` build-time scanning is introduced.

**`ReflectionConfig.java` additions:** `AgentCard.class`, `A2ASuspendedCorrelationEntity.class`, Nimbus JWT classes via `native-image.properties` in `arcclaw-a2a`.

---

## The Complete Mandate Table — All 10 Cross-Cutting Rules

| # | Phase | Mandate | Source |
|---|-------|---------|--------|
| 8 | P63 | `A2ASuspendedCorrelationEntity` (`@Entity`, Hibernate-managed) references existing `AgentCheckpoint` by FK | Gemini Blind Spot 3 + Perplexity |
| 9 | P63 | `nimbus-jose-jwt:9.37.3` for JWT wrapping; BouncyCastle for Ed25519 key gen; `quarkus-smallrye-jwt` excluded | Perplexity Gap 7 |


---

## Final Phase Summary

| Phase | Name | Order | New Modules | Key architectural decisions |
|-------|------|-------|-------------|----------------------------|
| **P63** | A2A | 5th | `arcclaw-a2a` | Async webhook (no SSE holds); `A2ASuspendedCorrelationEntity` Hibernate-managed; Ed25519 JWT via Nimbus + BouncyCastle; rule-based delegation; `ManagedExecutor` + virtual threads |

The roadmap is now fully hardened across four AI review rounds. Every critical issue has been adjudicated with a specific, codebase-grounded fix. P60 is the cleanest starting point — shall I produce the full P60 implementation prompt in your established format (`IMPLEMENTATION REQUIREMENTS`, component-by-component, test strategy, doc updates)?

