# Amsha Agent Developer Guide (AGENTS.md)

**All AI coding agents MUST adhere to these rules when editing this codebase.**

---

* **Import Alignment**: After editing Java, run `python3 ./fix_fqns.py && ./mvnw spotless:apply -q`
* **Documentation Verification**: After modifying any markdown (`.md`) files, run `python3 ./verify_docs.py` to check for broken links and anchor integrity.
* **SonarQube Gate**: Before committing, run `mise run sonar:issues` (all BLOCKER+CRITICAL) or `mise run sonar:issues -- -severity BLOCKER`

---

## 1. Critical Architectural Invariants

### 1.1 Transaction-Free Zone
LLM calls, external tool execution, and network requests **MUST NOT** run inside `@Transactional`. Do not nest `@Transactional(REQUIRES_NEW)` inside active writes.
**Why**: SQLite WAL mode starves or deadlock under concurrent access if connections are held during high-latency remote tasks. Keep transactions minimal, writes fast.

### 1.2 SQLite WAL & Safety Level
Do not alter SQLite safety config (e.g. `synchronous=NORMAL`) inside an active transaction — throws `SQLiteException` ("Safety level may not be changed inside a transaction").

### 1.3 Flyway Migration Parity
Every Flyway migration **MUST** have a matching migration in the other backend with the same version number and table semantics.

| Column Type | SQLite | PostgreSQL |
|---|---|---|
| Auto-increment PK | `INTEGER PRIMARY KEY AUTOINCREMENT` | `BIGSERIAL PRIMARY KEY` |
| Timestamps | `TEXT NOT NULL` (ISO-8601) | `TIMESTAMP WITH TIME ZONE NOT NULL` |
| Boolean | `BOOLEAN DEFAULT FALSE` | `BOOLEAN NOT NULL DEFAULT FALSE` |
| Unique constraint | `UNIQUE(col1, col2)` | `UNIQUE(col1, col2)` (same) |

**Locations**: `persistence-sqlite/src/main/resources/db/migration/sqlite/` / `persistence-postgres/src/main/resources/db/migration/postgresql/`

### 1.3.1 Persistence Backend Selection
Each adapter uses `@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite"|"postgres"|"fs")`. SQLite is default (`enableIfMissing = true`). **JVM mode**: set `AMSHA_PERSISTENCE_BACKEND` in `.env` — no rebuild. **Native image**: baked at build time, must rebuild. Tiered memory backend (`amsha.memory.tiered.backend`) is a separate property and can differ. See `docs/Architecture_Invariants.md` §5.24 for full details.

| Binary | Backend | Build command |
|---|---|---|
| `amsha-sqlite` | SQLite (default) | `./mvnw package -Dnative` |
| `amsha-pg` | PostgreSQL | `./mvnw package -Dnative -Damsha.persistence.backend=postgres` |
| `amsha-fs` | Filesystem | `./mvnw package -Dnative -Damsha.persistence.backend=fs` |

JVM mode is backend-agnostic: one JAR, switch via `.env`. Native builds need separate binaries per backend. Release archives contain all 3 per OS.

### 1.4 Admin REST Resource Pattern
All `@Path("/admin/...")` resources **MUST** use `resolveUserId()` (from `CronResource.java` — `SecurityContext.getUserPrincipal()` with `WorkspaceContext.getUserIdOrUnknown()` fallback). Write endpoints (PUT/POST/DELETE/PATCH) **MUST** gate behind `isInternal()` returning `Response.status(FORBIDDEN)` when false. Reads (GET) remain unprotected.

### 1.5 Heterogeneous Nullability in Sealed Interfaces
If a sealed interface method returns a nullable wrapper (`Boolean`, `Integer`), Javadoc **MUST** declare `@nullable` and document which subtypes return null. Every consumer **MUST** null-guard:
```java
// SAFE — Boolean.TRUE handles null
if (Boolean.TRUE.equals(step.success())) { ... }
// SAFE — explicit null check
if (step.success() != null && step.success()) { ... }
// UNSAFE — NPE when success() returns null
if (step.success()) { ... }  // DO NOT DO THIS
```
**Why**: `FinishStep.success()` always returns `true`, but `ParallelStep.success()` returns `null`. Auto-unboxing without null check causes NPE on specific execution paths. Enforced by `ReasoningStepSuccessNullSafetyTest` (ArchUnit).

### 1.6 JDK 25 Language Features (GA Only — No Preview)
**Use** (finalized): unnamed variables `_` (JEP 456, JDK 22), pattern matching `switch` (JEP 441, JDK 21), record patterns (JEP 440, JDK 21), sealed interfaces (JEP 409, JDK 17), pattern matching `instanceof` (JEP 394, JDK 16), text blocks (JEP 378, JDK 15), virtual threads (JEP 444, JDK 21), scoped values (JEP 481, JDK 25), flexible constructors (JEP 513, JDK 25).
**Do NOT use** (preview): structured concurrency (JEP 505), stream gatherers (JEP 485), module imports (JEP 511), compact source files (JEP 512), value classes (JEP 502).
```java
// ✅ Unnamed variable — intentionally discarded
var _ = conn.close();
// ✅ Pattern matching switch
return switch (step) {
    case ToolCallStep tc  -> tc.success() != null && tc.success();
    case FinishStep f     -> true;
    case ParallelStep p   -> Boolean.TRUE.equals(p.success());
};
// ✅ Pattern matching instanceof
if (response instanceof ToolResponse.ToolSuccess s) {
    return s.output();
}
// ❌ Preview features — DO NOT USE
import module java.base;  // JEP 511
```

### 1.7 Continuity Intent vs Follow-Up
`isExplicitContinuity` (try again, retry, redo, proceed, continue) **restores old goal**. Execution follow-ups (`execute`, `run it`, `do it`, `run java`, `run python`, etc.) use the **new goal** — do NOT restore stale context. Enforcement: `IntentClassificationService.CONTINUITY_PATTERN`, `AgentDispatchHandler.resolveGoal()`, `TextPatternUtils.isFollowUpHeuristic()`.

### 1.8 GraalVM SecureRandom Local Instantiation
`SecureRandom` instances MUST be created locally per use with `new SecureRandom()`.
MUST NOT hold `SecureRandom` in `static` or `static final` fields — GraalVM attempts
to snapshot the object into the image heap, causing `UnsupportedFeatureException`.
The byte array returned MUST be zeroed in a `finally` block after use
(defense-in-depth against heap inspection).

### 1.9 Strict Terminal Finish Rules
The coordinator persona MUST emit a `FINISH` action immediately after any successful
tool execution. No additional tool calls may follow a `FINISH` in the same turn.
`AgentStateMachineRunner` enforces a two-pass protocol: pass 1 sends tool results
with a terminal-mode hint; pass 2 sends a plain-text summary guidance. Violation:
Tool chaining after `FINISH` corrupts the state machine and produces orphaned
checkpoint states.

### 1.10 Telegram HITL Notification Flush
Channel callback handlers that bypass `AbstractDecisionReflex` (e.g., Telegram
inline keyboard approval callbacks) MUST call `flushPendingNotifications()`
after processing approval decisions. Without this flush, approval notifications
may be silently dropped, leaving the user without feedback after a HITL decision.

### 1.11 Fallback Session ID Derivation
`FallbackSessionIdResolver` MUST derive session IDs deterministically from the
three-tuple `(channelType, channelId, userId)`. Multi-channel sessions are isolated
by `channelType` — a Telegram user and a WebSocket user with the same numeric ID
MUST NOT share a session. Cron sessions (`sessionId.startsWith("cron-")`) MUST be
excluded from chat approval routing.

### 1.12 Group-Aware Escalation Order (Lokas)
Agent groups (Lokas) route tasks to group leads who decide: self-handle, consult family, or escalate out.
Escalation priority order is **sacred** — never reorder:
1. Role-specific rules (`escalateByRole`) — highest priority
2. New persona rules (`determineNewPersonaEscalation`)
3. Group-aware fallback (`tryIntraGroupEscalation`) — lowest priority
4. Sandbox code error fallback (→ Shiva)

**Why**: Group-aware routing is a FALLBACK. If it runs first, it intercepts cases that existing role-specific rules should handle (e.g., sentinel "enforce" should go to debugger, not to sibling sentinel).

Groups are **in-memory only** — no schema changes needed for SQLite/PG/FS. `AgentGroupRegistry` holds built-in groups; dynamic groups registered at runtime via `registerDynamic()`. Cross-backend parity: no Flyway migration required.

### 1.13 Escalation Keyword Specificity
Escalation keywords in `canRoleHandleGoal()` and `EscalationPatterns` **MUST** be specific enough to avoid false positives.
```java
// ❌ WRONG — "fix" matches "prefix", "fixture", "suffix"
containsAny(goal, "fix", "debug", "error")
// ✅ CORRECT — "fix bug" is specific
containsAny(goal, "fix bug", "debug", "error")
```
Rule: Test keywords against false-positive candidates before committing. Single words like `"fix"`, `"broken"`, `"patch"` are too broad — use `"fix bug"`, `"broken code"`, `"patch bug"`.

### 1.14 Role ID Canonicalization
All role ID returns from escalation/routing methods **MUST** use `AgentRoleEnum.fromString(id).toScopedName()` — not raw strings. Ensures lowercase canonical form.
```java
// ❌ WRONG — returns raw string from group member list
return sibling;
// ✅ CORRECT — returns canonical scoped name
return AgentRoleEnum.fromString(sibling).toScopedName();
```
`AgentRoleEnum.fromString()` **MUST** be wrapped in try-catch when called with potentially unknown role IDs (dynamic/skill roles). Falls back to raw string, logs warning.

### 1.15 Record Cross-Field Validation
Records with inter-field constraints **MUST** validate in the compact constructor:
```java
public record AgentGroup(String groupId, String leadRoleId, List<String> memberRoleIds, ...) {
    public AgentGroup {
        if (!memberRoleIds.contains(leadRoleId)) {
            throw new IllegalArgumentException("leadRoleId must be a member of the group");
        }
    }
}
```

### 1.16 Static Immutable Maps
Static data populated once and never modified **MUST** use `Map.copyOf()` — NOT `ConcurrentHashMap`.
```java
// ❌ WRONG — mutable concurrent map for static data
private static final Map<String, AgentGroup> BY_MEMBER = new ConcurrentHashMap<>();
// ✅ CORRECT — immutable snapshot, inherently thread-safe
private static final Map<String, AgentGroup> BY_MEMBER = Map.copyOf(temp);
```

### 1.17 CDI Qualifier Literal Pattern (`AnnotationLiteral`)
CDI qualifier literals **MUST** extend `AnnotationLiteral<T>` AND implement the annotation interface `T`. This is required by the CDI spec (Jakarta CDI §11.1) for programmatic lookup via `Instance.select(new MyQualifierLiteral("value"))`.
```java
// ✅ CORRECT — CDI spec requires implements TieredBackend
@SuppressWarnings("java:S2176")
public final class TieredBackendLiteral extends AnnotationLiteral<TieredBackend> implements TieredBackend { ... }
```
The `implements TieredBackend` triggers an IDE/Sonar warning ("annotation type should not be used as a superinterface") — suppress with `@SuppressWarnings("java:S2176")` and document why. **Do not** refactor to reflection or string-based selection.

---

## 2. Key Framework Components

### 2.1 Exception Classification (Chain of Responsibility)
LLM error classification is delegated to `LlmExceptionClassifierChain` subclasses. To add a new provider classifier: extend `LlmExceptionClassifierChain`, implement `classify(Throwable)`, use `delegate(t)` for unhandled causes, prepend/append in `LlmExceptionClassifier.java` static block. Use inherited `isRateLimitError(String)` — don't duplicate.

### 2.2 Channel Inbound Pipelines (Template Method)
All inbound handlers extend `AbstractInboundMessagePipeline`. Parse payload → resolve `chatId`+`text` → build `ChannelUser`+`ChannelMessage` → call `processInboundMessage(chatId, text, user, channelMessage)`.

### 2.3 Prompt Template Engine (Hardcoded Switch)
`PromptTemplateEngine.templateFor()` uses a hardcoded `switch` mapping `role.id()` → Qute `@Location` template. When adding a persona: create `templates/prompts/role-<name>.txt`, inject `@Inject @Location("prompts/role-<name>") Template nameTemplate`, add `case "<name>"`. **No compile-time safety** — runtime `IllegalStateException` if missing.

### 2.4 Prompt Registry (Multi-Backend Port)
System prompts stored in backend-agnostic `PromptRegistry` (SQLite/PG/FS via `PromptStorePort`). Auto-seeds from classpath `templates/personas/*.txt` + `templates/prompts/*.txt` on startup. SHA-256 drift detection auto-upgrades classpath changes but respects admin edits (version>1 or status≠ACTIVE). Falls back to classpath cache when no backend available. Admin API: `PUT/POST /admin/prompts/{id}`.

### 2.5 Configuration Property Sync Rule
When adding `@ConfigProperty`, update ALL of: `application.yaml` (`${ENV_VAR:default}`), `.env.example` (with comment), module `README.md` (if exists). Verify: `grep "MY_PROP" .env.example application.yaml`.

### 2.6 Optional Bean Resolution via BeanManager
For beans from optional Maven deps, use `BeanManager.getBeans(Object.class)` — NOT `Instance<Object>` or `@Any Instance<Object>`. Iterate set, check `bean.getBeanClass().getName()`, resolve via `beanManager.getReference()`, verify `getMethod()` before use. Verify: app log shows `[MCP] BeanManager returned N beans total` with N ≥ 10.

### 2.7 Filesystem Backend + Lucene
`amsha-persistence-fs` uses Lucene (`LuceneIndexManager`) for BM25 + optional vector kNN. JSON files under `{dataDir}/memory-index/` are source of truth; Lucene index under `{dataDir}/lucene-index/` is rebuilt from JSON on startup. Degrades to BM25-only if `EmbeddingModel` bean unavailable. Hybrid search merges via RRF (k=60). Config: `amsha.fs.data-dir`, `amsha.fs.lucene-index-dir`, `amsha.fs.vector-dimension`.

**Lucene Document Schema**: `id` (`StringField`, stored), `content` (`TextField`, stored + indexed), `metadata_json` (`StoredField`), `timestamp` (`LongPoint` + `StoredField`), `embedding` (`KnnFloatVectorField`, COSINE — optional).

### 2.8 ToolSearchIndex (Dynamic Tool Discovery)
In-memory Lucene index (`ByteBuffersDirectory`) over all tools. Reorders (not filters) `ToolSpecification` by BM25 relevance to goal. On `ParseException`, falls back to `QueryParser.escape()`. Rebuilt on `@PostConstruct`.

### 2.9 Role-Based Tool Governance Bypass
`ToolGovernanceGuard` has a role trust pre-flight check before op-level enforcement:
```java
boolean isTrustedRole = !roleRegistry.requiresApprovalForTools(roleEnum);
boolean isSensitiveOp = requiredOp == ToolOperation.NETWORK_OUTBOUND || requiredOp == ToolOperation.EXECUTE_SHELL;
if (isTrustedRole && !isSensitiveOp) return; // allow
```
Trusted roles (`requiresApproval=false`) skip non-sensitive policy checks. `NETWORK_OUTBOUND` and `EXECUTE_SHELL` always require HITL regardless of trust. `garuda` removed from `hitl-exempt-tools` — role trust now handles this.

### 2.10 EXECUTE_SHELL as Sensitive Op
`EXECUTE_SHELL` (sandbox_exec) is classified alongside `NETWORK_OUTBOUND` as a sensitive operation — always requires HITL for trusted roles. Add new sensitive ops to `ToolGovernanceGuard.checkPolicy()`.

### 2.11 Tool Guardrail Hardening
`SmartWebFetchTool` now enforces 3 retry attempts with linear backoff and strict URL
validation. The `url` parameter is mandatory for `browser_navigate` (Playwright).
The researcher persona MUST NOT call `headroom_retrieve` — use `recall_memory_search`
or `archival_memory_search` instead.

---

## 3. Code Conventions

### 3.1 No Hardcoded Tool Name Strings
Use `ToolCapability.ENUM.toolName()` instead of `"sandbox_exec"`, `"github"`, etc. Exceptions (not refactorable): `@ToolMetadata`/`@Tool` annotation attributes, `@WithDefault` defaults, Javadoc examples, user-input pattern matching, LLM prompt instructions. Cross-module: `ToolCapability` lives in `amsha-core` — modules without that dependency (e.g. `amsha-channel-api`) must keep hardcoded strings.

Tool parameter mode values (e.g., `"group_consult"`, `"solo"`) **MUST** be `static final String` constants in the tool class — not inline string literals. Use the constant in conditionals and code-generated strings. LLM prompt text (in `.txt` templates and `CoordinatorPromptSection`) remains as literal strings per the exception above.
```java
// ❌ WRONG
if ("group_consult".equals(mode)) { ... }
// ✅ CORRECT
static final String MODE_GROUP_CONSULT = "group_consult";
if (MODE_GROUP_CONSULT.equals(mode)) { ... }
```

### 3.2 Telegram Garbled Line Stripping
All outbound Telegram messages **MUST** pass through `stripGarbledLines()` before sending. Filters lines where >80% of non-space chars are `?`, `…`, em-dashes, `OTHER_PUNCTUATION`. Called in `TelegramChannelSender.sendMessageInternal()`.

### 3.3 SessionReaper Zombie Recovery — All Channels
On restart, auto-deny ALL stale `AWAITING_APPROVAL` checkpoints for all channel types (CLI, Telegram, WebSocket UI). Fires `ApprovalDecisionEvent` (Telegram keyboard expiry) + `agentSseService.complete()` (WebSocket SSE). CLI: `cliResultBridge.cancel()`.

### 3.4 Saraswati Disabled from Delegation
`saraswati` now has `requiresApproval=false` — other agents cannot delegate to the researcher role. Reason: read-only retrieval role; all roles have `garuda` for escalation. See `AgentRoleEnum.java` `DEFINITIONS.put(SARASWATI_ID, ...)`.

### 3.5 Prompt Keyword Specificity for Escalation
Escalation keywords in persona `.txt` files and `EscalationPatterns.java` **MUST** avoid single-word patterns that match unrelated text. After any prompt change, run:
```bash
./mvnw test -pl core -Dtest="PersonaEscalationServiceTest,EscalationPatternsTest"
```
Persona prompts should optimize for **LLM comprehension**, not mythological accuracy. Use "lead of your realm", "brother", "father" — not "step-son", "form of", "emanation of".

### 3.6 Duplicate Code Path Avoidance
When method A calls method B, and B already handles feature X, **do not** also handle X in A. Example: `tryIntraGroupEscalation()` was called in both `PersonaEscalationService.determineEscalationTarget()` AND `EscalationPatterns.determineNewPersonaEscalation()` (which is called by the former). Duplicate removed — group-aware fallback lives exclusively in `determineNewPersonaEscalation()`.

---

## 4. Maven Build & Dependency Resolution

* **Local repo sync**: After modifying a dependency module, run `./mvnw clean install -DskipTests` so dependent modules see the latest classes.
* **PMD gates**: 500 lines/file, 80 lines/method, cognitive complexity ≤ 50 (transitioning to 15). PMD runs during `verify` phase. SonarQube (`mise run sonar:issues`) is primary gate.
* **Version management**: All third-party dependency versions in parent `pom.xml` `<dependencyManagement>` via `<property>`. Module `pom.xml` entries **MUST NOT** have hardcoded `<version>`. Exceptions: plugin versions (managed separately), internal deps use `${project.version}`, BOM imports use their own property.
* **PostgreSQL conflict**: Native PG on `:5432` intercepts Podman container's PG. Detect: `lsof -i :5432`. Fix: stop/uninstall native PG service (see OS-specific commands in `docs/MANUAL_SETUP.md`).
* **Backend parity for all stores**: When adding or modifying any `*StorePort` interface, ALL 3 backend implementations MUST be created/updated simultaneously:
  - `amsha-persistence-sqlite` (SQLite — default, `@IfBuildProperty(enableIfMissing = true)`)
  - `amsha-persistence-postgres` (PostgreSQL — `@IfBuildProperty("postgres")`)
  - Either `amsha-persistence-fs` (Filesystem — `@IfBuildProperty("fs")`) OR the store lives in the `self-evolve` module with `persistence-fs` wrappers
  - Flyway V<n> migrations must exist in both `persistence-sqlite` and `persistence-postgres` (FS has no migrations).
  - **Verification**: `grep -rn '@IfBuildProperty.*sqlite\|@IfBuildProperty.*postgres\|@IfBuildProperty.*fs' --include='*Store*.java'` should return matches for all 3 backends per port.
  - **Known failures**: P104 (self-evolve FS missed), P104b (tiered memory FS missed), P104c (`%filesystem` profile missing `amsha.memory.tiered.backend`). This rule prevents recurrence.
* **Module CDI discovery**: Every non-app, non-deployment Maven module with CDI beans MUST include:
  1. `jandex-maven-plugin` in `<build><plugins>` section
  2. `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`
  
  Without these, `@ApplicationScoped` beans are **silently invisible** — no error, no warning, just missing beans. Compilation + unit test pass in isolation is NOT sufficient proof. After wiring into `amsha-app`, verify by grepping startup logs for bean-init messages (e.g., `[SelfEvolve] Enabled`). See `docs/Architecture_Invariants.md` §15 for the full protocol and the P104 self-evolve failure pattern.

---

## 5. Adding a New LLM Provider

Existing providers: `groq`, `openai`, `ollama`, `openrouter`, `claude-code`,
`cerebras`, `gemini`, `nvidia`, `omlx`, `generic-openai`.

1. **`RoutingChatModelProvider`** — `core/.../routing/<Name>LlmProviderFactory.java`
2. **`AgentChatModelProvider`** — `core/.../agent/reasoning/<Name>ProviderFactory.java`
3. **`LlmModelFactory.java`** — Add URL pattern to `URL_PROVIDER_MAP`
4. **`OnboardingResource.java`** — Add default model to `DEFAULT_MODELS`
5. **`.env`** — Add `AMSHA_<NAME>_BASE_URL`, `AMSHA_<NAME>_MODEL`, add to `AMSHA_INFINITE_LOOP_LLM_PROVIDERS`
6. **`.env.example`** — Same additions
7. **`arc-settings.ts`** + **`arc-onboarding-step2.ts`** — Add to `PROVIDERS`/`providers` arrays
8. **Verify**: `./mvnw compile -q` + `cd ui && npm run build`

**Generic OpenAI provider pattern:** Use `GenericOpenAiLlmProviderFactory.java` (routing)
and `GenericOpenAiProviderFactory.java` (agent reasoning) as templates. The generic
provider auto-detects `deepseek-reasoner` and injects `include_reasoning=true` workaround.
Requires `AMSHA_GENERIC_OPENAI_BASE_URL` and `AMSHA_GENERIC_OPENAI_API_KEY`.

---

## 6. Babysitter Integration

This project is babysitter-enabled for complex workflow orchestration.

**Invocation**: Use `/skill:babysit` or refer to the `cradle/user-install` / `cradle/project-install` processes.

**Project profile**: `.a5c/project-profile.json` — contains tech stack, architecture, conventions, and pain points.

**Quality gates**: `.a5c/quality-gates.json` — pre-commit checks and deploy gates.

**Mode**: semi-autonomous (babysitter pauses for approval on breakpoints).

**Recommended methodology**: evolutionary with spec-driven refinement.

**Relevant docs**:
- `CLAUDE.md` — has a ## Babysitter section with quick commands
- `.a5c/` — project-level babysitter configuration

## 7. Adding a New Persona (Agent Role)

1. **`AgentPersona.java`** — Add enum entry (`roleId`, `displayName`, `aliases`)
2. **`AgentRoleEnum.java`** — Add `static final` ID constant, instance, and `RoleDefinition` in `DEFINITIONS` (use `Map.ofEntries()` if >10)
3. **`templates/personas/<name>.txt`** — Persona identity (who they are, rules)
4. **`templates/prompts/role-<name>.txt`** — Role-specific prompt section
5. **`AgentProducer.java`** — Add `@Produces @ApplicationScoped @AgentRole(ID)` method
6. **`CoordinatorPromptSection.java`** — Add to Trimurti/Tridevi block
7. **`PromptTemplateEngine.java`** — Add `@Inject @Location("prompts/role-<name>")` + `case "<name>"` in `templateFor()`
8. **`DelegateToAgentTool.java`** — Add role ID to `defaultRoles` in `@ToolMetadata`
9. **`templates/prompts/delegate-to-agent-rules.txt`** — Add to "Available Specialists"
10. **`PersonaEscalationService.java`** — Add escalation paths in `determineNewPersonaEscalation()`
11. **`PromptInstructionLibrary.java`** — Add to system capabilities
12. **`application.yaml`** — Add role entry under `amsha.agents.roles`
13. **`AgentGroupRegistry.java`** — Add to appropriate group (Loka) if applicable
14. **Persona prompt** — Add `<GROUP_NAME> FAMILY AWARENESS` section with escalation rules
15. **Verify**: `./mvnw compile -q`, run `AgentRoleTest` + `PersonaEscalationServiceTest`
16. Update `telegram-test-amsha.txt`

**⚠ WARNING**: Items 7-9 are static lists/annotations — no compile-time safety. Always verify manually.

---

## 8. GraalVM Native-Image Reflection Registration

**Rule**: Every new Java file needs evaluation for `@RegisterForReflection`. JVM-mode success ≠ native-image safety.

| Class Type | Needs Registration? |
|---|---|
| `record` (DTOs for JSON/JDBI) | **YES** |
| JDBI DAO interfaces (`@SqlQuery`/`@SqlUpdate`) | **YES** |
| JDBI inner `*DtoRow` classes | **YES** |
| `@ApplicationScoped` in persistence modules | **YES** |
| CDI beans in non-persistence modules | NO (ArC handles it) |
| Enums, `@ConfigMapping`, `@Qualifier` | NO |

**Module config files** (add to the module's `*ReflectionConfig.java`):
`amsha-persistence-api` → `TieredMemoryReflectionConfig`, `amsha-persistence-sqlite` → `SqlitePersistenceReflectionConfig`, `amsha-persistence-postgres` → `PostgresPersistenceReflectionConfig`, `amsha-persistence-fs` → `FsReflectionConfig`, `amsha-core` → `CoreReflectionConfig`, `amsha-app` → `AppReflectionConfig`/`ChannelReflectionConfig`, `amsha-channel-telegram` → `TelegramReflectionConfig`, `amsha-onboarding` → `OnboardingReflectionConfig`, `amsha-mcp` → `McpReflectionConfig`, `amsha-rag` → `RagReflectionConfig`.

**Native-image runtime init** (`native-image.properties` in `amsha-app`):
Libs using FFM/MethodHandle/native access need `--initialize-at-run-time=<package>`. **Mark the ENTIRE package** (e.g. `org.apache.lucene`), not individual classes — GraalVM embeds static fields from build-time classes into the image heap, causing whack-a-mole errors when marking classes individually. Also mark CDI beans with `@PostConstruct` that trigger the library. Do NOT use `pom.xml additional-build-args` (Quarkus splits on commas breaking the arg).
```properties
# ✅ CORRECT — marks entire package + sub-packages
--initialize-at-run-time=org.apache.lucene

# ❌ WRONG — triggers whack-a-mole (each class's static deps cause new errors)
--initialize-at-run-time=org.apache.lucene.store.PosixNativeAccess
```

**Foreign Function registration** (`reachability-metadata.json`):
FFM downcalls need explicit registration. Current: `posix_madvise`, `getpagesize` (Lucene PosixNativeAccess). On `MissingForeignRegistrationError`, register the exact leaf type from the error message.

**Verification**: `python3 ./fix_fqns.py && ./mvnw compile -DskipTests -q` for compilation; `mise build:native-local` for full native build.

---

## 9. Phase 12: Hardening & Cleanup (Memory Tiered)

### 9.1 Admin REST Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/admin/memory/recall/search` | GET | Search recall memory with query, sessionId, limit, includeSummaries |
| `/admin/memory/recall/purge` | POST | Purge expired recall messages (requires internal API key) |
| `/admin/memory/archival/search` | GET | Search archival memory with query, limit, chunkTypes |
| `/admin/memory/archival/{chunkId}` | GET | Get specific archival chunk by ID |
| `/admin/memory/archival/{chunkId}` | DELETE | Delete archival chunk (requires internal API key) |
| `/admin/memory/archival/evict` | POST | Evict low-confidence chunks (requires internal API key) |
| `/admin/memory/core` | GET | List all core memory blocks |
| `/admin/memory/core/{blockKey}` | GET | Get specific core memory block |
| `/admin/memory/core/{blockKey}` | PUT | Upsert core memory block (requires internal API key) |
| `/admin/memory/core/{blockKey}` | DELETE | Delete core memory block (requires internal API key) |
| `/admin/memory/directives` | GET | List all directives |
| `/admin/memory/directives/reconcile` | POST | Reconcile directive lifecycle (requires internal API key) |

### 9.2 Observability Metrics (Micrometer)

| Metric | Type | Description |
|---|---|---|
| `memory.recall.search` | Counter | Total recall memory searches |
| `memory.recall.search.latency` | Timer | Recall memory search latency (p50, p95, p99) |
| `memory.recall.result.count` | DistributionSummary | Distribution of recall search result counts |
| `memory.recall.purge` | Counter | Total recall memory purge operations |
| `memory.archival.search` | Counter | Total archival memory searches |
| `memory.archival.search.latency` | Timer | Archival memory search latency (p50, p95, p99) |
| `memory.archival.result.count` | DistributionSummary | Distribution of archival search result counts |
| `memory.archival.confidence` | DistributionSummary | Distribution of archival chunk confidence scores |
| `memory.archival.evict` | Counter | Total archival memory eviction operations |

### 9.3 Retention & Eviction Jobs

| Schedule | Job | Description |
|---|---|---|
| `0 0 2 * * ?` | `purgeExpiredRecallMessages` | Purge recall messages older than `amsha.memory.retention.recall-retention-days` (default: 30) |
| `0 0 3 * * ?` | `evictLowConfidenceArchivalChunks` | Evict archival chunks with confidence below threshold and older than minAgeDays |

### 9.4 Configuration Properties

```yaml
amsha:
  memory:
    retention:
      enabled: ${AMSHA_MEMORY_RETENTION_ENABLED:true}
      recall-retention-days: ${AMSHA_MEMORY_RETENTION_RECALL_RETENTION_DAYS:30}
      archival-eviction-threshold: ${AMSHA_MEMORY_RETENTION_ARCHIVAL_EVICTION_THRESHOLD:0.3}
      archival-min-age-days: ${AMSHA_MEMORY_RETENTION_ARCHIVAL_MIN_AGE_DAYS:30}
```

### 9.5 Latency Targets

| Operation | Target | Phase |
|---|---|---|
| Core Memory render | <1ms | Phase 1 |
| Recall search (session) | <10ms | Phase 3 |
| Recall search (cross-session) | <50ms | Phase 3 |
| Archival hybrid search | <50ms | Phase 5 |
| Self-evolve SIMPLE retrieval | <10ms | Phase 9 |
| Self-evolve DEEP synthesis | 2-5s (offline) | Phase 10 |

### 9.6 Phase 12 Verification Commands

```bash
# Run Phase 12 tests
./mvnw test -pl amsha-app -Dtest="MemoryLatencyP95Test"
./mvnw test -pl amsha-app -Dtest="MemoryConcurrencyTest"
./mvnw test -pl amsha-app -Dtest="TieredMemoryDisableRegressionTest"
./mvnw test -pl amsha-app -Dtest="EndToEndDisabledBehaviorTest"
./mvnw test -pl amsha-app -Dtest="EndToEndTieredMemoryTest"
./mvnw test -pl amsha-app -Dtest="TieredMemoryHealthDtoTest"
./mvnw test -pl amsha-app -Dtest="MemoryMetricsObserverTest"
./mvnw test -pl amsha-app -Dtest="MemoryRetentionSchedulerTest"
./mvnw test -pl amsha-app -Dtest="RecallMemoryAdminResourceTest"
./mvnw test -pl amsha-app -Dtest="ArchivalMemoryAdminResourceTest"

# Run all admin endpoint tests
./mvnw test -pl amsha-app -Dtest="*AdminResource*Test"

# Verify retention config
grep "AMSHA_MEMORY_RETENTION" .env.example app/src/main/resources/application.yaml
```

---

## 10. Dynamic SkillsJars (Agentic Skills) Execution Architecture

Dynamic skills (loaded from classpath `.jar` dependencies under `META-INF/skills/`) act as **instruction-based wrappers** for the agent. 

### How SkillsJars Work:
1. **JVM Scanning & Prompt Injection**: On startup, `SkillsJarSkillProvider` locates skills jars, parses their `SKILL.md` (which details system prompts, allowed tools, and rules), and registers the dynamic role with the system's `RoleRegistry`.
2. **Tool Bindings & CLI Dependencies**: 
   - A skill jar (e.g. `remote-browser` or `jdb-debugger`) defines permitted tool capabilities (such as `sandbox_exec`).
   - The skill's `SKILL.md` instructs the LLM on which command-line utilities to invoke inside the sandbox (e.g. `browser-use` CLI or `jdb` commands).
3. **Execution Environment Requirement**: 
   - When the agent executes a dynamic skill role, it utilizes `sandbox_exec` to run bash scripts.
   - Therefore, the execution sandbox (or host fallback environment) must pre-install the respective CLI binaries (such as `browser-use`, `jdb`, `chromium`, etc.) so that the shell execution does not fail with `command not found`.

### Cloud Dependency Vetting (P105 Lesson):
Before adding a SkillsJar to `app/pom.xml`, **extract and read** the skill's `SKILL.md` (`jar xf <jar> META-INF/skills/.../SKILL.md`) and verify:
- Does it work offline / in an air-gapped sandbox?
- Does it require cloud API keys or paid SaaS authentication?
- Are all CLI dependencies installable in `Dockerfile.toolrunner`?

**Cloud-only skills MUST NOT be bundled** unless the API key is provisioned in `.env` and injected via `SandboxSecretResolver`. A skill that silently fails without an API key triggers infinite Shiva auto-heal loops (`heal:shiva:...:heal:shiva:...`) until workspace paths exceed OS limits. See `docs/Architecture_Invariants.md` §24.5.

