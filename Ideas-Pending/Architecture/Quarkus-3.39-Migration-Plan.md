# Quarkus 3.38.3 → 3.39.3 Migration Plan (Migration #3)

- **Status**: Decision-pending (dry-run verified, BOM coordinates confirmed; migration path not yet ratified per policy / Issue 10)
- **Date**: 2026-09-12 (Asia/Kolkata)
- **Decision owners**: Amsha Agent maintainers
- **Source platform**: Quarkus `3.38.3` (regular stream)
- **Target platform**: Quarkus `3.39.3` (regular stream; base for the upcoming `3.40 LTS`)
- **Precedent ADR**: [ADR-Quarkus-Upgrade-Strategy](./ADR-Quarkus-Upgrade-Strategy.md) (covers the 3.27.2 → 3.27.5.1 → 3.33.3.1 → 3.38.3 migrations)
- **Research note**: [Quarkus 3.38 Upgrade Research](./Quarkus-Upgrade-Research.md)
- **Verification Evidence**: `app/target/rewrite/rewrite.yaml`, `app/target/rewrite/rewrite.log`, `target/rewrite/rewrite.patch`

## 0. Decision gate — read this first (policy tension)

`docs/DEPENDENCY_UPGRADE_POLICY.md` (written 2026-08-25, after the 3.38.3
migration) states:

> "Quarkus upgrades are LTS-only." and "Quarkus `3.40 LTS` is tracked as the
> next target for **September 2026 (targeted)**."

Today is **2026-09-12**. `3.39` is a **regular (non-LTS)** stream, and `3.40
LTS` is imminent (targeted this month). The project is *currently running*
`3.38.3` — also a regular stream — so the "LTS-only" wording in the policy is
aspirational; the running reality is a regular-stream cadence.

Two valid paths:

- **Path A — migrate to `3.39.3` now (proposed staging checkpoint).** Quarkus explicitly asks
  early adopters to move to 3.39 so issues surface before the 3.40 LTS freezes.
  3.39 *is* the 3.40 LTS base, but the follow-up `3.39.3 → 3.40 LTS` step is a
  **minor-stream upgrade** (3.39 → 3.40 crosses a minor version boundary), not a
  patch bump. It must be treated as a full migration in its own right: a fresh
  updater dry-run, migration-guide review, and a complete re-run of the §6
  verification matrix. It is **not inherently low-risk**. This is consistent with
  the policy's note that "the 3.40 work must begin with a fresh updater dry-run
  and migration-guide review." Maintainers may run Phase 1–3 gates locally and
  choose to defer full native release/soak promotion until `3.40 LTS` lands.
- **Path B — wait for `3.40 LTS`, migrate `3.38.3 → 3.40 LTS` directly (one
  hop, LTS).** Cleanest under a strict reading of "LTS-only." Only sensible if
  3.40 LTS ships within days and the team prefers a single LTS landing.

**Decision status: PENDING human ratification.** This plan does not yet endorse a
path. The governing `DEPENDENCY_UPGRADE_POLICY.md` and Issue 10 require an
LTS-only path targeting `3.40 LTS`, whereas `3.39.3` is a non-LTS stream. Before
any execution step runs:

- **If Path A is ratified**, the policy MUST first be explicitly amended to permit a
  non-LTS 3.39 staging step (and Issue 10 updated to record it); **or**
- **Path B** is taken — wait for `3.40 LTS`, then migrate `3.38.3 → 3.40 LTS` directly.

Either choice is reversible; the `3.38.3` artifact remains the rollback boundary.
The Phase 0–4 procedure below applies to whichever path is ratified.

**Policy follow-up (action item):** if Path A is ratified, amend
`DEPENDENCY_UPGRADE_POLICY.md` (and Issue 10) to record the 3.39 staging decision
before any promotion, so the "LTS-only" line stops contradicting the running
state. This plan does not edit that policy file.

## 1. Current state (verified 2026-09-12)

| Item | Value | Note |
| --- | --- | --- |
| `quarkus.platform.version` | `3.38.3` | `pom.xml` line 51 |
| Current branch | `P124` | A **feature** branch — migration needs its own branch |
| Maven wrapper | `3.9.16` | Already satisfied by the prior migration; no toolchain bump |
| JDK | 25 (Temurin via mise) | Unchanged; 3.39 supports Java 25 |
| Panache source usage | **None** | `quarkus-hibernate-orm-panache` is present only as an `<exclusion>` (4x) on LangChain4j dependencies in `app/pom.xml`; **zero `.java` files reference any Panache type** |
| Prior migration commits | `cafafcfd1` (3.27.5.1→3.33.3.1), `c2d624435` (3.33.3.1→3.38.3) | This is migration #3 |

## 2. Breaking-change surface: 3.38 → 3.39

### 2.1 Quarkus Core

The official [Migration Guide 3.39](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.39)
contains essentially **one** breaking area in core, and it does not touch Amsha's code:

- **Quarkus Data / Panache package rename** (`io.quarkus.hibernate.panache` →
  `io.quarkus.data.hibernate`; `PanacheEntity` → `ManagedEntity`/`RecordEntity`;
  `PanacheQuery` → `DataQuery`). Auto-handled by `quarkus update`. **Not
  applicable** — Amsha has no Panache source usage. Dry-run verified that zero Java files
  were matched by this recipe. Confirm via `mvn dependency:tree` that LangChain4j does
  not transitively pull in unwanted ORM or Data dependencies.

Release-note behavior changes that matter in core:

- **Reflection-free Jackson serializers reverted to opt-in (PR #56072).** In
  3.38 they were on by default; in 3.39 they are opt-in again. For Amsha this is
  a **positive normalization**: the heavy native reflection config for
  LangChain4j chat-memory JSON (`$ChatMessageMixin` / `Lucene104Codec`
  registrations in `CoreReflectionConfig` / `FsReflectionConfig`) is less likely
  to be bypassed. `ChatMessageJsonRoundTripTest` remains the guard. No action,
  but re-run that test explicitly. To re-enable the optimization later, set
  `quarkus.rest.jackson.optimization.enable-reflection-free-serializers=true`.
- **Jackson Databind minor bump** (`2.22.0` → `2.22.2`).
- **SmallRye OpenAPI bump** (`4.3.3` → `4.3.5`).
- **Post-quantum cryptography (PQC) in TLS registry** — three new opt-in config
  properties (`quarkus.tls.key-exchange-groups`, `quarkus.tls.pqc-enforcement-policy`,
  `quarkus.tls.ssl-engine`). Additive; no default behavior change.

### 2.2 Quarkiverse MCP Server: 1.13.1 → 2.0.0 (Major Version Jump)

The 3.39.3 member BOM upgrades Quarkiverse MCP Server from `1.13.1` to **`2.0.0`**.
Amsha's `amsha-mcp` module provides MCP tools over stdio and HTTP. The key changes are:

- **HTTP DNS Rebinding Protection**: MCP 2.0.0 strictly validates the incoming `Host` and
  `Origin` headers against the listening interface (`http: match Origin host exactly in DNS rebinding protection`).
  HTTP clients connecting over non-standard hostnames or through proxies must ensure
  headers match the server's expected origin.
- **Spec Schema Update**: Emits 2026-07-28 MCP specification schema payloads and adds
  `MISSING_REQUIRED_CLIENT_CAPABILITY`.
- **Error Code Renumbering**: MCP error codes are updated to match the latest draft specification.
- **Cache Control**: New support for `ttlMs` and `cacheScope` in tool and resource results.
- **Exception Handling**: `WrapBusinessErrorInterceptor` refactored to never wrap control-flow exceptions.

#### 2.2.1 Additional MCP 2.0.0 breaking changes (verified against release notes)

Three further breaking changes surfaced in the 2.0.0 release that the dry-run
recipe does not rewrite and that `amsha-mcp` must be checked against:

- **JSON-RPC batching removed (#840).** Both transports reject batched
  JSON-RPC requests. **Impact to verify**: confirm `amsha-mcp` and any external
  MCP clients send single requests only. The module's tests use `McpAssured`
  single-call patterns, but verify no batch payloads in `PodmanMcpTransport*`,
  `McpClientService`, or `ExternalMcpToolAdapter`.
- **`McpProtocolVersion` enum → `final class` (#865).** **Non-impacting for
  Amsha** — `grep` of `mcp/src` finds no reference to `McpProtocolVersion`, so
  no source rework is needed.
- **SSE artifact relocations removed (#839).** **Non-impacting for Amsha** —
  `amsha-mcp` depends on `quarkus-mcp-server-stdio` and
  `quarkus-mcp-server-http` only (never `quarkus-mcp-server-sse`), so the
  removed relocated Maven coordinates do not affect the build.

The §5 risk register and §7 acceptance criteria below capture the one item that
requires explicit verification (JSON-RPC batching).

### 2.3 Apache Camel Quarkus: 3.38.0 → 3.39.0 (Camel 4.21 → 4.22)

The 3.39.3 platform upgrades Apache Camel from `4.21.0` to `4.22.0`.
The dry-run triggered `io.quarkus.updates.camel.camel422.CamelQuarkusMigrationRecipe`.
Amsha's `channel-telegram` relies heavily on Camel:

- **Multicast EIP**: Correctly honors `UseOriginalAggregationStrategy`.
- **InMemorySagaCoordinator**: Now propagates finalization outcomes instead of completing immediately.
- **PQC Cryptographic Signing**: Payload strings are signed strictly as UTF-8.
- **Session Eviction in MCP Transport**: Automatic keep-alive pings (30s) and idle TTL (5m).

### 2.4 LangChain4j: Core 1.17.2 → 1.19.0 (Extension 1.13.1)

The LangChain4j member BOM moves `quarkus-langchain4j-core` to `1.13.1` and
`langchain4j-core` across two minor versions (`1.18.x` and `1.19.0`).
Chat-memory serialization, tool execution schemas, and model adapters must be validated
via existing test suites (`ChatMessageJsonRoundTripTest`, provider integration tests).

**Not in scope (already absorbed by the 3.27.2 → 3.38.3 ADR):** JUnit 6
relocations (3.31), Mutiny 2→3 major (3.27→3.38), Camel `junit5`→`junit` rename
(3.34), Jandex plugin skew (parent already `3.6.0`), Agroal major, Flyway 11→12
major, Micrometer strict tag consistency (3.34), Arc default-method interception
(3.37), OTel `LateBoundSpanProcessor` removal (3.38), Host-header validation
(3.38). All proven at the 3.38.3 checkpoint; re-verify lightly, do not re-engineer.

## 3. Platform / member-BOM targets (verified via dry-run & Maven Central)

The 3.39.3 ecosystem targets have been empirically extracted from published BOMs and verified:

| Dependency / Coordinate | 3.38.3 Baseline | 3.39.3 Confirmed | Control Mechanism |
| --- | --- | --- | --- |
| `quarkus-bom` | `3.38.3` | `3.39.3` | Quarkus Platform BOM (`${quarkus.platform.version}`) |
| `quarkus-camel-bom` | `3.38.3` | `3.39.3` | Camel Quarkus `3.39.0` (Camel `4.22.0`) |
| `quarkus-langchain4j-bom` | `3.38.3` | `3.39.3` | Extension `1.13.1`, Core `1.19.0` |
| `quarkus-mcp-server-bom` | `3.38.3` | `3.39.3` | MCP Server `2.0.0` (Major jump) |
| `quarkus-jdbi.version` | `1.10.1` | `1.10.1` | Pinned Quarkiverse property (Jdbi `3.54.0`) |
| `quarkus-jdbc-sqlite.version` | `3.0.11` | `3.0.11` | Pinned Quarkiverse property (latest published) |
| `bouncycastle.version` | `1.84` (pinned) | **`1.85.2` (managed)** | **Update pin to `1.85.2`** to avoid silent downgrade |
| `lucene.version` | `10.4.0` | `10.4.0` | Explicit pin (native `Lucene104Codec` invariant) |
| `djl.version` | `0.33.0` | `0.33.0` | Explicit pin (offline MPNet tokenizer invariant) |
| `onnxruntime.version` | `1.27.0` | `1.27.0` | Explicit pin (single-threaded native runtime) |
| `jboss-logmanager.version` | `3.2.2.Final` | `3.2.2.Final` | Pinned property (aligned with platform) |
| `bytebuddy.version` | `1.18.8` | `1.18.8` | Pinned property (aligned with platform) |
| `commons-lang3.version` | `3.20.0` | `3.20.0` | Pinned property (aligned with platform) |
| `archunit.version` | `1.5.0` | `1.5.0` | Pinned property (JUnit 6 engine compatible) |

The invariants from the ADR hold:

1. One `quarkus.platform.version`; `quarkus.version` stays `${quarkus.platform.version}`.
2. Member BOMs (`quarkus-bom`, `quarkus-langchain4j-bom`, `quarkus-camel-bom`,
   `quarkus-mcp-server-bom`) all import at the same `${quarkus.platform.version}`.
3. JDBI (`quarkus-jdbi.version`) and SQLite JDBC (`quarkus-jdbc-sqlite.version`)
   stay explicit pins; SQLite JDBC remains `3.0.11`.
4. Lucene pinned at `10.4.0` (native `Lucene104Codec` reflection invariant).
5. DJL tokenizers `0.33.0`, ONNX Runtime `1.27.0`, jboss-logmanager `3.2.2.Final`,
   byte-buddy `1.18.8`, commons-lang3 `3.20.0`, ArchUnit `1.5.0` keep existing pins.
6. **Bouncy Castle Platform Alignment**: `<bouncycastle.version>` in root `pom.xml` must be
   bumped from `1.84` to `1.85.2` so it matches what `quarkus-bom:3.39.3` manages, preventing
   a silent downgrade defect and fulfilling the pending update in `DEPENDENCY_UPGRADE_POLICY.md`.

## 4. Phased execution

### Phase 0 — isolate and baseline

1. Human creates a dedicated branch `quarkus-3.39` (do **not** migrate on
   `P124`). No unrelated feature/schema/format changes.
2. Record baseline: `java --version`, `./mvnw --version`, GraalVM/Mandrel 25,
   platform BOMs, `help:effective-pom`, `dependency:tree -Dverbose`, JVM test
   counts, native artifact hashes, runtime smoke results.
3. Establish a green `3.38.3` baseline with the Section 6 matrix. Resolve or
   explicitly waive any pre-existing failure before attributing anything to 3.39.
4. Wrapper is already `3.9.16` — skip the toolchain bump.

### Phase 1 — updater proposal + version bump

1. Run the `quarkus-update` skill as pre-flight drift check (per ADR §5.1), then
   generate the proposal from the application module:

   ```bash
   ./mvnw -f app/pom.xml \
     io.quarkus.platform:quarkus-maven-plugin:3.39.3:update \
     -DplatformVersion=3.39.3 -Drewrite=false

   ./mvnw -f app/pom.xml \
     io.quarkus.platform:quarkus-maven-plugin:3.39.3:update \
     -DplatformVersion=3.39.3 -DrewriteDryRun=true
   ```

2. Review the generated `target/rewrite/rewrite.patch`.
   **CRITICAL**: The dry-run patch attempts to replace `<quarkus.version>${quarkus.platform.version}</quarkus.version>`
   with `<quarkus.version>3.39.3</quarkus.version>`. **Do not apply `rewrite.patch` raw.**
3. Manually update root `pom.xml`:
   - Set `<quarkus.platform.version>3.39.3</quarkus.platform.version>`
   - Retain `<quarkus.version>${quarkus.platform.version}</quarkus.version>`
   - Set `<bouncycastle.version>1.85.2</bouncycastle.version>` (align with platform BOM)
4. Verify `dependency:tree` across reactor modules:
   - Confirm Camel is `3.39.0`, MCP Server is `2.0.0`, and LangChain4j is `1.13.1` / `1.19.0`.
   - Confirm no transitive Hibernate/Panache dependencies are introduced.

### Phase 2 — formatting, PMD gate, then compilation & augmentation

> Repository required order (AGENTS.md): after Java edits run `fix_fqns.py` +
> `spotless:apply`; before building or handing off Java changes run the
> module-scoped PMD gate. Formatting and PMD therefore run **before** the full
> build below.

1. `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q` (formatting — required after Java edits).
2. `./mvnw -pl app,deployment -am pmd:check -DskipTests` (PMD complexity gate — required before build/handoff).
3. Compile `deployment` + `app` first (custom augmentation build steps):
   `ContextRegistrationPhaseBuildItem`, `CustomScopeBuildItem`,
   `UnremovableBeanBuildItem`, `ReflectiveClassBuildItem`,
   `NativeImageResourceBuildItem`.
4. Full reactor: `./mvnw clean install -DskipTests`.

### Phase 3 — behavioral gates (proportional, not full re-run)

- Full JVM suite: `mise run build:test` (and `build:fs`, `build:pg`).
- REST/Jackson round-trip over REST resources **and** LangChain4j guardrail
  payloads — confirm the reflection-free-Jackson revert did not change
  serialization.
- **`ChatMessageJsonRoundTripTest`** explicitly (LC4J chat-memory JSON native invariant).
- **MCP Server 2.0.0 verification**:
  - Test stdio transport via `amsha-mcp`.
  - Test HTTP transport with valid and invalid `Host`/`Origin` headers to ensure DNS rebinding protections do not break allowed clients.
- **Telegram routing**: Run `channel-telegram` test suite to verify Camel 4.22 route execution and Telegram event mapping.
- WebSockets Next, Qute exception paths, event-loop guards (`Context.isOnEventLoopThread()`),
  `@RunOnVirtualThread` handlers, metrics/tracing diff vs 3.38.3 baseline.
- TLS 1.3 (PostgreSQL), Agroal pool limits (`max-size: 3` SQLite / `10` Postgres), Flyway checksum stability on both backends.
- Bouncy Castle 1.85.2 security provider registration check.

### Phase 4 — native, release, docs, soak

1. Build all native variants: `mise run build:native-local-all`.
2. SQLite native smoke: `mise run smoke:local-runtime -- native --binary dist/amsha-sqlite`.
3. Release evidence: `bash scripts/verify_native_matrix.sh`,
   `bash scripts/verify_onnx_release_provenance.sh`.
4. Regenerate OpenAPI + TS DTOs if `target/openapi.yaml` changed (SmallRye
   OpenAPI moves to 4.3.5); rerun `ts-dto` profile + UI type-check.
5. Update version badges / docs (`README.md`, `docs/*`); `python3 ./scripts/verify_docs.py`.
6. Canary/soak the exact artifact (startup, auth, persistence, approval, MCP,
   Telegram, metrics, traces, latency, memory, shutdown).
7. **Decision gate**: promote `3.39.3` (keep `3.38.3` deployable). Then schedule
   the `3.39.3 → 3.40 LTS` promotion: fresh dry-run + re-run of this matrix at
   the LTS pin.

## 5. 3.39-specific risk register (additions)

| Risk | Why it matters | Control |
| --- | --- | --- |
| Policy contradiction | `DEPENDENCY_UPGRADE_POLICY.md` says LTS-only; 3.39 is regular stream | Resolve Path A/B decision + update policy before promoting |
| MCP Server 2.0.0 major bump | DNS rebinding protection, schema updates, renumbered error codes | Test HTTP and stdio transports with external MCP clients |
| MCP 2.0.0 JSON-RPC batching removal (#840) | Batched requests rejected on stdio + HTTP | Verify `amsha-mcp` and clients send single requests only (no batch payloads in `PodmanMcpTransport*`, `McpClientService`, `ExternalMcpToolAdapter`) |
| Camel 4.21 → 4.22 | EIP multicast aggregation, saga coordinator propagation | Run Telegram channel integration and route tests |
| Bouncy Castle silent downgrade | `quarkus-bom:3.39.3` has 1.85.2; keeping 1.84 in root POM downgrades it | Update `<bouncycastle.version>` to `1.85.2` and verify security provider |
| OpenRewrite alias destruction | Dry-run replaces `${quarkus.platform.version}` with literal | Apply version bump manually in `pom.xml`; do not apply raw patch |
| Reflection-free Jackson revert | 3.39 flips default serialization path vs 3.38 | Run REST + LC4J JSON round-trip + `ChatMessageJsonRoundTripTest` |
| 3.40 LTS imminent | Double-hop risk if 3.40 LTS ships soon | Keep 3.38.3 artifact deployable; treat 3.39.3 as staging checkpoint |

## 6. Verification matrix (reuse from ADR §8)

Run from repo root. Commands requiring credentials must not print secrets.

```bash
python3 ./scripts/fix_fqns.py
./mvnw spotless:apply -q
./mvnw -pl app,deployment -am pmd:check -DskipTests
./mvnw clean install -DskipTests
mise run build:test
mise run build:fs
mise run build:pg
mise run sonar:issues          # before human checkpoint commit
./mvnw help:effective-pom -Doutput=target/quarkus-effective-pom.xml
./mvnw -pl app -am dependency:tree -Dverbose
mise run build:native-local-all
mise run smoke:local-runtime -- native --binary dist/amsha-sqlite
bash scripts/verify_native_matrix.sh
bash scripts/verify_onnx_release_provenance.sh
python3 ./scripts/verify_docs.py
git diff --check
```

## 7. Acceptance criteria

1. Selected Quarkus + member BOM versions documented and coherent (single
   `quarkus.platform.version`).
2. Every used third-party extension managed or explicitly versioned with
   compatibility evidence (JDBI 1.10.1, SQLite 3.0.11).
3. Bouncy Castle aligned to `1.85.2` with security provider native initialization confirmed.
4. Quarkiverse MCP Server 2.0.0 HTTP (DNS rebinding / origin checks) and stdio transports verified; no JSON-RPC batching dependency in `amsha-mcp` transports or external clients.
5. Camel 4.22 Telegram routes and event mapper verified.
6. Custom `deployment` module compiles, augments, passes tests.
7. Test discovery counts not silently decreased.
8. Formatting, PMD, JVM, all 3 persistence backends, REST/Jackson + LC4J JSON
   round-trip, native (all variants), and release-evidence gates pass.
9. Exact artifact completes canary/soak.
10. `3.38.3` artifact + config remain deployable (rollback boundary).

## 8. Rollback

Select the previously validated `3.38.3` revision, artifact, and configuration.
No in-place reverse OpenRewrite. No Flyway/data change in this migration. Retain
per-checkpoint: source rev + reviewed patch, JDK/Maven/Mandrel versions,
platform/member-BOM matrix, dependency trees, JVM + native digests, config/Flyway
identity, test/smoke/PMD/matrix/provenance results, soak baselines.

## 9. Primary sources

- [Quarkus 3.39 release](https://quarkus.io/blog/quarkus-3-39-released/)
- [Migration Guide 3.39](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.39)
- [Quarkus update guide](https://quarkus.io/guides/update-quarkus)
- [Quarkiverse MCP Server 2.0.0 Release Notes](https://github.com/quarkiverse/quarkus-mcp-server/releases/tag/2.0.0)
- [Apache Camel 4.22 Upgrade Guide](https://camel.apache.org/manual/camel-4x-upgrade-guide-4_22.html)
- [Quarkus releases / support](https://quarkus.io/releases/)
- Precedent: [ADR-Quarkus-Upgrade-Strategy](./ADR-Quarkus-Upgrade-Strategy.md),
  [Quarkus 3.38 Upgrade Research](./Quarkus-Upgrade-Research.md),
  [Dependency Upgrade Policy](../../docs/DEPENDENCY_UPGRADE_POLICY.md)
