# ArcClaw — Quarkus Upgrade: 3.23 → 3.27.2
## Antigravity IDE Implementation Prompt (v2 — Post 2-AI Review)

---

## ARCHITECTURE CONTEXT PROMPT (Send First — Wait for ACKNOWLEDGED)

```
Role: Senior Java Architect, Dependency Manager & Senior Java Developer
Task: Quarkus platform upgrade from 3.23.x to 3.27.2

Architecture Context:
ArcClaw is a production-grade AI Agent Kernel. Multi-module Maven:
  arcclaw-parent → arcclaw-channel-api, arcclaw-core, arcclaw-deployment, arcclaw-app

Key runtime stack (all version-sensitive):
  - Java 25 + Quarkus 3.23.x (current) → target 3.27.2 (confirmed LTS patch)
  - The Quarkus version property in arcclaw-parent/pom.xml is:
      <quarkus.version>3.23.0</quarkus.version>   ← this is the property to change
    Do NOT create a new quarkus.platform.version property. Update quarkus.version only.
  - quarkus-maven-plugin: tracks Quarkus platform version
  - camel-quarkus: Apache Camel Quarkus — tracks Quarkus version number exactly
    (camel-quarkus 3.23.x → 3.27.2)
  - quarkus-langchain4j: extension currently 0.26.1 — bumps to extension 1.2.0,
    which packages LangChain4j core library 1.5 underneath. This is a MAJOR API
    break from the core library perspective (0.26.1 → 1.5). See breaking changes.
  - GraalVM Native Image: native profile must still compile after upgrade.
  - SQLite WAL, Hibernate ORM, SmallRye FT, Mutiny — pulled via Quarkus BOM.
  - Community SQLiteDialect: NOT in Quarkus core — must be verified separately
    against Hibernate ORM 7 (introduced in Quarkus 3.24).

Current test baseline: 535/535 (403 core + 132 app).
This upgrade is COMPLETE ONLY when the test suite is green at 535/535.

Upgrade policy:
  - Version bumps and mandatory API fixes only.
  - No new features, no refactoring, no logic changes.
  - All changes confined to pom.xml files and API call sites forced by the upgrade.

## Known Expected Breaking Changes (NOT "if errors arise" — these WILL happen)

BREAKING CHANGE 1 — LangChain4j core 0.26.1 → 1.5 (major API version break):
  The quarkus-langchain4j extension bumps from 0.26.1 to 1.2.0. That extension
  packages LangChain4j core library 1.5 underneath. The scope of API changes is
  from core 0.26.1 → 1.5 — a larger jump than early 1.x migration notes describe.
  Known renamed factory methods:
    AiMessage.from(text)                  → AiMessage.aiMessage(text)
    UserMessage.from(text)                → UserMessage.userMessage(text)
    SystemMessage.from(text)              → SystemMessage.systemMessage(text)
    ToolExecutionResultMessage.from(...)  → verify signature (may have changed)
  Affected files in ArcClaw: LangChainReasoningEngine, ChatHistoryAssembler,
  ReasoningStepParser, SystemPromptBuilder (and any other class calling these methods).
  These files WILL NOT COMPILE after the version bump. Budget mandatory fix time.

BREAKING CHANGE 2 — Hibernate ORM 7 (introduced in Quarkus 3.24):
  (a) Annotation processor artifact: hibernate-jpamodelgen → hibernate-processor
      Check pom.xml annotation processor paths — update if explicitly pinned.
  (b) Community SQLiteDialect: ArcClaw uses a community dialect, NOT Quarkus core.
      After the BOM bump, verify the SQLiteDialect dependency still resolves and
      compiles against Hibernate ORM 7. This is the highest-risk item.
  (c) InstantToTextConverter: verify AttributeConverter<Instant,String> compiles
      under Jakarta Persistence 3.2 (unchanged contract, but confirm).
  (d) Any direct Session injection (not EntityManager): check for CDI restrictions.
      ArcClaw uses raw EntityManager — this should be fine, but confirm.

Acknowledge this architecture context.
Do not write any code yet.
Reply with ACKNOWLEDGED and wait for me to send P1 Prompt.
```

---

## P1 PROMPT — COMPATIBILITY GATE (Run Before Any Changes)

```
## P1 — Compatibility Gate

Before modifying any pom.xml, answer all four items below. Report findings
for each before proceeding. Do not upgrade anything until all four are confirmed.

### Item 1 — Quarkus 3.27.2 is the target version

The target version is already confirmed: 3.27.2 (released January 21, 2026).
You do not need to search for this. The version property to update is:
  <quarkus.version>3.23.0</quarkus.version>
  Target: <quarkus.version>3.27.2</quarkus.version>

Do NOT create a new property. Do NOT change quarkus.platform.version — that
property does not exist in this project. Update quarkus.version only.

Report: Confirm you can see <quarkus.version> in arcclaw-parent/pom.xml and
its current value is 3.23.0.

### Item 2 — Camel Quarkus version

Camel Quarkus tracks the Quarkus platform version number exactly.
  Current:  camel-quarkus 3.23.x
  Target:   camel-quarkus 3.27.2

The version property for camel-quarkus in arcclaw-parent/pom.xml will be either
a separate property or the same ${quarkus.version} (the actual property name in
this project). Find and report it. No internet search needed — the version is 3.27.2.

### Item 3 — LangChain4j BOM: groupId and upgrade path

This item has two variants depending on the current pom.xml. Find it and report:

  VARIANT A — If arcclaw-parent uses groupId io.quarkiverse.langchain4j:
    The current import is:
      groupId:    io.quarkiverse.langchain4j
      artifactId: quarkus-langchain4j-bom
      version:    0.26.1  (referenced via <langchain4j.quarkus.version>0.26.1</langchain4j.quarkus.version>)
    Upgrade path: change groupId to io.quarkus.platform AND set version to
    ${quarkus.version} (your actual property name — NOT quarkus.platform.version).
    The platform-managed io.quarkus.platform:quarkus-langchain4j-bom:3.27.2
    bundles quarkus-langchain4j extension 1.2.0 and LangChain4j core 1.5.

  VARIANT B — If arcclaw-parent already uses groupId io.quarkus.platform:
    Simply updating ${quarkus.version} to 3.27.2 in Step 1 is sufficient.
    No additional change needed. Confirm and proceed.

Report: Which variant applies, and the current groupId + version in pom.xml.

⚠️ MANDATORY: Regardless of variant, the underlying LangChain4j core library
jumps from 0.26.1 → 1.5. This introduces breaking API changes (see Architecture
Context). These WILL cause compilation failures that MUST be fixed in P2 Step 4.
This is not optional.

### Item 4 — Breaking change inventory for migration path 3.23 → 3.27

Read the following migration guides and report only items that apply to ArcClaw:
  https://quarkus.io/guides/migration-guide-3.24
  https://quarkus.io/guides/migration-guide-3.25
  https://quarkus.io/guides/migration-guide-3.26
  https://quarkus.io/guides/migration-guide-3.27

Focus specifically on:
  - Quarkus REST (@Path, @RunOnVirtualThread changes)
  - SmallRye Fault Tolerance annotation changes
  - CDI / ArC behavior changes
  - Agroal pool interceptor API
  - @ConfigMapping interface changes
  - ScopedValue or virtual thread handling
  - Hibernate ORM 7 annotation processor artifact relocation
  - Any jakarta.* API namespace changes

Note: The LangChain4j and Hibernate ORM 7 breaks are already known (Architecture
Context). Flag any ADDITIONAL breaks beyond those two.

Report all four items. Wait for go-ahead before making any changes.
```

---

## P2 PROMPT — UPGRADE EXECUTION

```
## P2 — Upgrade Execution

Compatibility gate confirmed. Execute in this exact order. Do not skip steps.

### Step 1 — Update version properties in arcclaw-parent/pom.xml

Update the Quarkus version property to 3.27.2:
  <quarkus.version>3.27.2</quarkus.version>

Do NOT create a new quarkus.platform.version property. The existing
<quarkus.version> is the single source of truth for the platform version.
Also update the quarkus-maven-plugin if pinned separately (use same version).

### Step 2 — Update Camel Quarkus to 3.27.2

Update the camel-quarkus version property to 3.27.2.
If Camel Quarkus already uses ${quarkus.version}, no separate change needed.
Verify in pom.xml and report.

### Step 3 — Update LangChain4j BOM (variant-specific)

VARIANT A (currently io.quarkiverse.langchain4j groupId):
  Change the BOM import:
    FROM: <groupId>io.quarkiverse.langchain4j</groupId>
          <artifactId>quarkus-langchain4j-bom</artifactId>
          <version>0.26.1</version>  (or via <langchain4j.quarkus.version>)
    TO:   <groupId>io.quarkus.platform</groupId>
          <artifactId>quarkus-langchain4j-bom</artifactId>
          <version>${quarkus.version}</version>  ← use the actual property, not a literal

VARIANT B (already io.quarkus.platform groupId):
  The ${quarkus.version} bump in Step 1 already handles this.
  No additional change needed. Confirm and proceed.

### Step 4 — Fix LangChain4j 1.x API call sites (MANDATORY, not conditional)

This step WILL be required. Do not skip it.

Run compilation first to get the full error list:
  ./mvnw clean compile -pl arcclaw-core,arcclaw-channel-api,arcclaw-app -am

For each LangChain4j compilation error, apply the known API fixes:
  AiMessage.from(text)                  → AiMessage.aiMessage(text)
  UserMessage.from(text)                → UserMessage.userMessage(text)
  SystemMessage.from(text)              → SystemMessage.systemMessage(text)
  ToolExecutionResultMessage.from(...)  → check LangChain4j 1.x javadoc for new signature

Search ALL source files for every renamed factory method — do not rely on compiler
errors alone (some call sites may be in conditional paths):
  grep -r "AiMessage\.from\|UserMessage\.from\|SystemMessage\.from\|ToolExecutionResultMessage\.from" \
    --include="*.java" arcclaw-core arcclaw-app arcclaw-channel-api

Fix every occurrence. Add a comment on each fixed line:
  // LangChain4j 0.26.x→1.x migration: renamed from .from() to factory method

After fixing, re-run compile to confirm zero LangChain4j errors remain.

### Step 5 — Fix Hibernate ORM 7 issues

Run compile again after Step 4 to isolate Hibernate-specific failures:
  ./mvnw clean compile -pl arcclaw-core -am

Fix in this order:

  5a. Annotation processor artifact (if pom.xml has explicit processorPath):
      Change: hibernate-jpamodelgen → hibernate-processor
      (groupId org.hibernate.orm for both)

  5b. SQLiteDialect — CRITICAL RISK ITEM:
      After the Hibernate ORM 7 BOM bump, run:
        ./mvnw clean compile -pl arcclaw-core -am 2>&1 | grep -i "sqlite\|dialect"
      If the community SQLiteDialect no longer resolves:
        Option 1 (preferred): check if a Hibernate ORM 7-compatible SQLiteDialect
          version is available on Maven Central and update the dependency.
        Option 2 (fallback): if no compatible version exists, document the blocker
          and STOP the upgrade. Do not proceed past this point — report to operator.
          A broken SQLiteDialect means ArcClaw's persistence layer is non-functional.

  5c. InstantToTextConverter: confirm it still compiles. No code change expected
      but explicitly verify: ./mvnw clean compile -pl arcclaw-core -am -Dtest=none

  5d. Check for any removed Hibernate ORM 7 methods used in raw EntityManager calls.
      ArcClaw uses em.persist(), em.merge(), em.find(), em.createQuery() — all survive
      into Hibernate ORM 7. No changes expected. Confirm by compilation only.

### Step 6 — Fix any remaining migration guide issues from P1 Item 4

For each additional breaking change identified in the migration guide review,
apply the minimal required fix with a comment:
  // Quarkus 3.23→3.27 migration: <reason>

If zero additional breaking changes were found in P1 Item 4, skip this step.

### Step 7 — Full test suite

Run the complete regression:
  ./mvnw clean test -pl arcclaw-core,arcclaw-app -am

Expected: 535/535 passing (403 core + 132 app).

If tests fail:
  a) Determine if failure is caused by the version upgrade (fix it) or is
     a pre-existing issue (report it — do not mask it).
  b) Fix only upgrade-caused failures.
  c) Re-run until 535/535 or until a blocker is confirmed (see 5b above).

### Step 8 — Verify native image compiles

⚠️ WARNING: GraalVM native compilation takes 5–10 minutes and pins the CPU
at 100%. Do NOT time out waiting for this. Start the process, stream or tail
the output, and wait for BUILD SUCCESS or BUILD FAILURE.

  ./mvnw clean package -Pnative -DskipTests -pl arcclaw-app -am

If native compilation fails with a reflection error introduced by the upgrade:
  - Fix in native-image.properties or the existing McpBuildStep.
  - Do NOT add arcclaw-mcp imports to ReflectionConfig.java.

If native compilation fails due to SQLiteDialect (see Step 5b blocker):
  - Stop. Report to operator. Do not attempt workarounds.

Report outcome of all steps before proceeding to P3.
```

---

## P3 PROMPT — VERIFICATION AND DOCUMENTATION

```
## P3 — Verification and Documentation

### Verification checklist

Run each command and confirm pass:

1. ./mvnw clean test -pl arcclaw-core -am
   Expected: 403/403 core tests passing.

2. ./mvnw clean test -pl arcclaw-app -am
   Expected: 132/132 app tests passing.

3. ./mvnw clean package -DskipTests
   Expected: BUILD SUCCESS. Quarkus 3.27.2 banner in output.

4. Confirm version in built artifact:
   grep "quarkus.version" arcclaw-parent/pom.xml
   Expected: 3.27.2

Report: "535/535 passing on Quarkus 3.27.2" before updating docs.

### Documentation updates

PLAN.md — Add upgrade entry before Phase 59:
  - Quarkus Upgrade 3.23 → 3.27.2: Platform BOM, Camel Quarkus, and
    quarkus-langchain4j bumped to 3.27.2. quarkus-langchain4j extension bumps
    to 1.2.0, packaging LangChain4j core 1.5 underneath. Factory method renames
    fixed: AiMessage.aiMessage(), UserMessage.userMessage(), SystemMessage.systemMessage().
    Hibernate ORM 7 SQLiteDialect verified. 535/535 tests passing.
    Unlocks quarkus-mcp-server-stdio:1.10.3 + quarkus-mcp-server-http:1.10.3
    for Phase 59 MCP integration.

WALKTHROUGH.md — Update version table in infrastructure section:
  | Component                          | Before    | After                              |
  |------------------------------------|-----------|-------------------------------------|
  | Quarkus Platform                   | 3.23.x    | 3.27.2                             |
  | Camel Quarkus                      | 3.23.x    | 3.27.2                             |
  | quarkus-langchain4j (extension)    | 0.26.1    | 1.2.0 (platform-managed, 3.27.2)  |
  | LangChain4j (core library)         | 0.26.1    | 1.5                                |

TROUBLESHOOTING.md — Add upgrade note:
  "If reverting to Quarkus 3.23.x: set <quarkus.version> back to 3.23.0, pin
  camel-quarkus to 3.23.x, and revert the quarkus-langchain4j BOM to
  io.quarkiverse.langchain4j:quarkus-langchain4j-bom:0.26.1. Also revert all
  LangChain4j 1.x factory method fixes:
    AiMessage.aiMessage()     → AiMessage.from()
    UserMessage.userMessage() → UserMessage.from()
    SystemMessage.systemMessage() → SystemMessage.from()
  Mixed versions between Quarkus core, Camel, and LangChain4j cause CDI boot
  failures."

  "If SQLiteDialect fails under Hibernate ORM 7 and a compatible version cannot
  be found on Maven Central, the upgrade must be reverted entirely. The SQLite
  WAL persistence layer is not optional in ArcClaw."
```

---

## Post-Upgrade: P59 MCP Gate Update

Once the upgrade is verified green at 535/535 on Quarkus 3.27.2, the Phase 59
P1 Quarkiverse gate changes to the following. ArcClaw is now on 3.27.2, so it
must use MCP 1.10.x — the 1.7.3 line targeted Quarkus 3.22–3.23. The `sse`
artifact was renamed to `http` in 1.8.0, so `http` is the correct artifact for
the HTTP/SSE transport at 1.10.3.

Update the Phase 59 P1 gate profile to test BOTH transport artifacts:

```xml
<!-- Gate option A — STDIO transport -->
<dependency>
  <groupId>io.quarkiverse.mcp</groupId>
  <artifactId>quarkus-mcp-server-stdio</artifactId>
  <version>1.10.3</version>
</dependency>

<!-- Gate option B — HTTP transport (renamed from sse in 1.8.0) -->
<dependency>
  <groupId>io.quarkiverse.mcp</groupId>
  <artifactId>quarkus-mcp-server-http</artifactId>
  <version>1.10.3</version>
</dependency>
```

Run two separate gate commands (one per transport):
  ./mvnw clean package -pl arcclaw-mcp -am -Pmcp-smoke-test-stdio -DskipTests
  ./mvnw clean package -pl arcclaw-mcp -am -Pmcp-smoke-test-http  -DskipTests

Report PASS/FAIL for each. The P59 implementation path depends on the result.
If BOTH pass: use Quarkiverse path for both transports.
If ONLY stdio passes: use Quarkiverse for stdio, hand-rolled for HTTP.
If NEITHER passes: full hand-rolled JSON-RPC 2.0 path.

---

## Adjudication Summary

| ID | Source | Decision | Change Applied |
|---|---|---|---|
| MCP versions | Gemini | ACCEPT | Post-upgrade gate uses 1.10.3 + `http` (not 1.7.3 + `sse`) |
| BOM alignment | Gemini | PARTIAL | Single property for Quarkus+Camel; LangChain4j needs groupId check |
| Native timeout | Gemini | ACCEPT | Step 8 warns 5–10min, instructs stream/tail |
| C1 LangChain4j API break | Perplexity v1 | ACCEPT | Step 4 mandatory; grep covers all call sites |
| C2 Hibernate ORM 7 | Perplexity v1 | ACCEPT | Step 5 sub-steps; SQLiteDialect hard STOP if breaks |
| M1 LangChain4j groupId ambiguity | Perplexity v1 | ACCEPT | P1 Item 3 covers both groupId variants |
| Self: 3.27.2 pinned | Self | NEW | Pinned throughout |
| Self: SQLiteDialect rollback | Self | NEW | Hard STOP documented |
| Self: LangChain4j revert doc | Self | NEW | TROUBLESHOOTING revert instructions |
| **C1 Property name** | **Perplexity v2** | **ACCEPT** | **`quarkus.platform.version` → `quarkus.version` everywhere (P1 Item 1, P2 Steps 1–3, P3 grep). Actual property name confirmed from pom.xml.** |
| **C2 Extension vs core library** | **Perplexity v2** | **ACCEPT** | **Architecture Context, PLAN.md, WALKTHROUGH table, TROUBLESHOOTING corrected: extension=1.2.0, core library=1.5. Distinct values, separately documented.** |
| **H1 SystemMessage.from() missing** | **Perplexity v2** | **ACCEPT** | **`SystemMessage.from` added to grep command in P2 Step 4 and to API fixes table. Found in ChatHistoryAssembler and LangChainReasoningEngine.** |

*Upgrade prompt v2. No new application logic. Unlocks Phase 59 MCP artifacts 1.10.3 on Quarkus 3.27.2.*
