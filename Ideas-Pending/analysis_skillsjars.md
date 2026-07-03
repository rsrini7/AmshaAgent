# SkillsJars Integration Research & Proposal (Revised)

This document presents a comprehensive analysis of [SkillsJars](https://www.skillsjars.com/) and outlines the recommended path for integrating it with Amsha Agent.

**Revision**: Corrected to reflect actual codebase state. The original analysis overestimated the integration effort — Amsha Agent already has a mature skill infrastructure (`SkillProvider` SPI, `ClasspathSkillProvider`, `SkillLoader`, `SkillRegistry`) that supports the exact pattern SkillsJars requires.

> **Dev Workflow Note**: During active development, DB data migrations are not needed — delete the `data/` folder instead of running Flyway migrations. SkillsJars integration does not touch the database layer.

---

## 1. What is SkillsJars?

SkillsJars is a Maven Central registry for AI Agent Skill definitions, packaging `.md` skill files (adhering to the [agentskills.io](https://agentskills.io/specification) standard) into JARs.

### Key Characteristics:
*   **Coordinate Format**: Group ID is `com.skillsjars`, and the Artifact ID is constructed from the GitHub org, repo, and skill path: `com.skillsjars:org__repo__skill:<version>`.
*   **JAR Structure**: Inside the JAR, skills and supporting files are located at:
    ```
    /META-INF/skills/<org>/<repo>/<skill-name>/SKILL.md
    /META-INF/skills/<org>/<repo>/<skill-name>/scripts/... (optional)
    /META-INF/skills/<org>/<repo>/<skill-name>/references/... (optional)
    ```
*   **Standard YAML Frontmatter**:
    ```yaml
    ---
    name: code-reviewer
    description: Reviews code for bugs and style issues
    license: Apache-2.0
    compatibility: Requires Python 3.10+
    allowed-tools: Bash Read
    ---
    System prompt body...
    ```

---

## 2. Existing Amsha Skill Infrastructure (Critical Context)

**The analysis must start here.** Amsha Agent already has a fully functional, production-grade skill system. The integration effort is additive, not foundational.

### 2.1 Existing Components

| Component | Location | Purpose |
|---|---|---|
| `SkillProvider` | `core/.../skill/SkillProvider.java` | SPI interface for skill source discovery. Has `load()`, `sourceName()`, `priority()`. |
| `ClasspathSkillProvider` | `core/.../skill/ClasspathSkillProvider.java` | Loads skills from `/skills/index.txt` on classpath. **Already GraalVM Native Image compatible.** |
| `DatabaseSkillProvider` | `core/.../skill/DatabaseSkillProvider.java` | Loads skills from database via `SkillStorePort`. Priority = 10 (highest). |
| `SkillLoader` | `core/.../skill/SkillLoader.java` | Orchestrator. Iterates all `SkillProvider` beans, merges by priority, compiles patterns, registers dynamic roles via `roleRegistry.registerDynamic()`. |
| `SkillRegistry` | `core/.../skill/SkillRegistry.java` | In-memory registry of `CompiledSkillEntry` objects. |
| `SkillDefinitionParser` | `core/.../skill/SkillDefinitionParser.java` | Parses YAML frontmatter + prompt body into `SkillDefinition`. Uses Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false` (tolerant of extra fields). |
| `SkillFrontmatter` | `core/.../skill/SkillFrontmatter.java` | Jackson DTO: `name`, `description`, `trigger-patterns`, `tool-capabilities`, `max-iterations`. |
| `SkillDefinition` | `core/.../skill/SkillDefinition.java` | Immutable record: `id`, `name`, `description`, `triggerPatterns`, `toolCapabilities`, `maxIterations`, `systemPrompt`. |
| `CompiledSkillEntry` | `core/.../skill/CompiledSkillEntry.java` | Runtime entry with pre-compiled regex patterns and derived `RoleDefinition`. |
| `SkillReflex` | `core/.../skill/SkillReflex.java` | Handles `/skills` command to list active skills. |
| `SkillResource` | `app/.../skill/SkillResource.java` | REST API: `GET /skills`, `POST /skills` (upsert), `DELETE /skills/{name}`, `GET /skills/refresh`. |
| `SkillStorePort` | `persistence-api/.../SkillStorePort.java` | Persistence port. All 3 backends implemented (SQLite, PostgreSQL, FS). |
| `index.txt` | `core/src/main/resources/skills/index.txt` | Static index file for `ClasspathSkillProvider`. Currently empty. |
| `SkillAwareIntentDetector` | `core/.../routing/SkillAwareIntentDetector.java` | Routes user intents to skills based on `trigger-patterns` regex. |
| `SkillFileWatcher` | `core/.../skill/SkillFileWatcher.java` | Watches `{workspaceRootDir}/skills/` for `.md` changes with 2-second debounce. Triggers `skillLoader.reloadAll(true)` on change. SkillsJars files extracted to this directory get automatic hot-reload with zero code changes. |

### 2.2 How Skills Become Agent Roles

This is the critical flow that the original analysis missed:

```java
// SkillLoader.java — onStart() and reloadAll()
for (CompiledSkillEntry entry : finalList) {
    roleRegistry.registerDynamic(entry.definition().name(), entry.roleDefinition());
}
```

Every loaded skill automatically becomes a dynamic agent role that can be delegated to via `DelegateToAgentTool` (garuda). The `RoleDefinition` is constructed with:
- `requiresApproval = false` (see security section below)
- `toolCapabilities` = mapped from `tool-capabilities` frontmatter
- `aliases` = `Set.of(skillName.toLowerCase())`

### 2.3 Priority Scheme

| Provider | Priority | Semantics |
|---|---|---|
| `DatabaseSkillProvider` | 10 | Highest — user-created skills override everything |
| `ClasspathSkillProvider` | 100 | Lowest — bundled baseline skills |
| Custom providers | 10–100 | For SkillsJars, use **50** (between DB and classpath) |

---

## 3. Gap Analysis: SkillsJars vs. Amsha Agent

| Aspect | SkillsJars Standard | Amsha Agent Current | Gap & Resolution |
|---|---|---|---|
| **Frontmatter Format** | `name`, `description`, `license`, `compatibility`, `metadata`, `allowed-tools` | `name`, `description`, `trigger-patterns`, `tool-capabilities`, `max-iterations` | SkillsJars skills lack `trigger-patterns` — they will only be accessible via explicit delegation (garuda) or `/skills` listing. No auto-routing. |
| **Tool Naming** | Space-separated human-readable names: `Bash`, `Read`, `Edit`, `browser_navigate` | Amsha `ToolCapability` enum values: `SANDBOX_EXEC`, `GITHUB`, `BROWSER_NAVIGATE` | **Mapping required.** See §3.1 below. |
| **Frontmatter Parsing** | Jackson tolerant (`FAIL_ON_UNKNOWN_PROPERTIES = false`) | Same — already tolerant | `SkillFrontmatter` needs an `allowed-tools` field added, plus mapping logic in `SkillDefinitionParser`. |
| **GraalVM Native** | N/A (just JARs on classpath) | `ClasspathSkillProvider` already works with index file | **No gap.** Existing infrastructure handles this. |
| **Dynamic Role Registration** | N/A | `SkillLoader` calls `roleRegistry.registerDynamic()` | SkillsJars skills will automatically become delegatable agent roles. No extra work needed. |

### 3.1 Tool Name Mapping Table

SkillsJars `allowed-tools` values must be mapped to Amsha `ToolCapability` enum names. **Important**: The existing `SkillLoader.compileTools()` uses a fuzzy match (uppercase + strip underscores), but this algorithm **won't handle SkillsJars names** — an explicit mapping function in `SkillDefinitionParser` is required.

| SkillsJars `allowed-tools` | Amsha `ToolCapability` | Fuzzy Match Works? | Notes |
|---|---|---|---|
| `Bash` | `SANDBOX_EXEC` | **No** (`BASH` ≠ `SANDBOXEXEC`) | Explicit mapping needed |
| `Read` | *(no equivalent)* | N/A | File reading is implicit — no tool gate needed |
| `Edit` | *(no equivalent)* | N/A | File editing is implicit — no tool gate needed |
| `Write` | *(no equivalent)* | N/A | File writing is implicit — no tool gate needed |
| `browser_navigate` | `BROWSER_NAVIGATE` | Yes | `BROWSERNAVIGATE` = `BROWSERNAVIGATE` |
| `github` | `GITHUB` | Yes | Exact match after uppercase |
| `web_search` | `WEB_SEARCH_BRAVE` or `WEB_SEARCH_SERPER` | No | Map to whichever is configured |
| `garuda` / `delegate` | `DELEGATE_TO_AGENT` | **No** (`GARUDA` ≠ `DELEGATETOAGENT`) | Explicit mapping needed. Note: `ToolCapability.DELEGATE_TO_AGENT.toolName()` is `"garuda"`, not the enum name. |

**Unknown tools**: Log a warning and exclude (matching existing `SkillLoader.compileTools()` behavior).

---

## 4. Security Analysis (Critical)

### 4.1 The Problem

SkillsJars skills are **community-contributed** and hosted on Maven Central. A malicious skill could:
1. Declare `allowed-tools: Bash` to gain code execution
2. Contain prompt injection in the `systemPrompt` body
3. Request excessive `max-iterations` to cause resource exhaustion

### 4.2 Current Gap

In `SkillLoader.addSafe()`, all skills get `requiresApproval = false`:

```java
RoleDefinition roleDef = new RoleDefinition(
    canonicalRoleName,
    "Skill: " + def.name(),
    def.description(),
    def.maxIterations(),
    false,  // requiresApproval ← ALWAYS FALSE
    false,  // hitlExempt
    toolsResult.valid(),
    Set.of(def.name().toLowerCase(Locale.ROOT)));
```

This means SkillsJars skills with `SANDBOX_EXEC` (Bash) capability can execute code **without human-in-the-loop approval**.

### 4.3 Proposed Security Model

| Source | `requiresApproval` | Rationale |
|---|---|---|
| Database (`DatabaseSkillProvider`) | `false` | User-created, trusted |
| Classpath (`ClasspathSkillProvider`) | `false` | Bundled with the app, developer-vetted |
| SkillsJars (`SkillsJarSkillProvider`) | **`true` for sensitive ops** | Third-party, untrusted |

Implementation: Add a config property:

```yaml
amsha:
  skills:
    skillsjars:
      require-approval-for-sensitive: true  # Default: true
```

In `SkillLoader.addSafe()`, when the source is `"skillsjars"` and the config is enabled, set `requiresApproval = true` if the skill declares any sensitive tool capabilities.

**Sensitive ToolCapability Set** — Must match AGENTS.md §2.9 (`ToolGovernanceGuard` always requires HITL for these regardless of role trust):

```java
private static final Set<ToolCapability> SENSITIVE_CAPABILITIES = Set.of(
    ToolCapability.SANDBOX_EXEC,       // maps to EXECUTE_SHELL (CRITICAL risk)
    ToolCapability.BROWSER_NAVIGATE,   // maps to NETWORK_OUTBOUND
    ToolCapability.WEB_SEARCH_BRAVE,   // maps to NETWORK_OUTBOUND
    ToolCapability.WEB_SEARCH_SERPER,  // maps to NETWORK_OUTBOUND
    ToolCapability.DELEGATE_TO_AGENT   // maps to DELEGATE_AUTONOMOUS (MEDIUM risk)
);
```

> **Do NOT use a category-based check** (`ToolCategory.COMPUTE || ToolCategory.NETWORK`). The mapping between `ToolCapability.category()` and `ToolOperation` is not 1:1 — e.g., `DELEGATE_TO_AGENT` is `INTERNAL` category but maps to `DELEGATE_AUTONOMOUS` (MEDIUM risk). Use an explicit `Set<ToolCapability>` instead.

---

## 5. Recommended Implementation Plan

### Option A: Extend Existing `ClasspathSkillProvider` [RECOMMENDED]

**Why**: The infrastructure already exists. We're adding a new source, not a new architecture.

#### Step 1: Add SkillsJars Dependencies to `app/pom.xml`

```xml
<dependency>
    <groupId>com.skillsjars</groupId>
    <artifactId>browser-use__browser-use__remote-browser</artifactId>
    <version>2026_02_23-1d154e1</version>
</dependency>
```

#### Step 2: Update `ClasspathSkillProvider` to Scan `META-INF/skills/`

Modify `ClasspathSkillProvider.load()` to also scan for SkillsJars JAR entries:

```java
@Override
public List<SkillDefinition> load() {
    List<SkillDefinition> skills = new ArrayList<>();

    // 1. Load from existing /skills/index.txt (bundled skills)
    loadFromIndex(skills);

    // 2. Load from META-INF/skills/**/SKILL.md (SkillsJars JARs)
    loadFromMetaInf(skills);

    return skills;
}

private void loadFromMetaInf(List<SkillDefinition> skills) {
    try {
        Enumeration<URL> resources = Thread.currentThread()
            .getContextClassLoader()
            .getResources("META-INF/skills");
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            scanDirectory(url, skills);
        }
    } catch (Exception e) {
        LOG.warnf("Failed to scan META-INF/skills: %s", e.getMessage());
    }
}

private void scanDirectory(URL url, List<SkillDefinition> skills) {
    try {
        if ("jar".equals(url.getProtocol())) {
            // SkillsJars JARs on classpath — scan JAR entries
            try (JarFile jarFile = ((JarURLConnection) url.openConnection()).getJarFile()) {
                jarFile.stream()
                    .filter(entry -> entry.getName().endsWith("/SKILL.md"))
                    .forEach(entry -> {
                        try (InputStream is = jarFile.getInputStream(entry)) {
                            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            skills.add(parser.parse(content));
                        } catch (Exception e) {
                            LOG.warnf("Failed to load skill from JAR entry %s: %s",
                                entry.getName(), LogUtil.rootMessage(e));
                        }
                    });
            }
        } else if ("file".equals(url.getProtocol())) {
            // Exploded directory (e.g., during development or after skillsjars:extract)
            Path dir = Path.of(url.toURI());
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(p -> p.endsWith("SKILL.md"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            skills.add(parser.parse(content));
                        } catch (Exception e) {
                            LOG.warnf("Failed to load skill from %s: %s",
                                path, LogUtil.rootMessage(e));
                        }
                    });
            }
        }
    } catch (Exception e) {
        LOG.warnf("Failed to scan META-INF/skills directory %s: %s", url, LogUtil.rootMessage(e));
    }
}
```

**Alternative: Zero-Extraction via `SkillFileWatcher` (Option A from SkillsJarsReference.md)**
Instead of modifying `ClasspathSkillProvider`, extract SkillsJars files to `{workspaceRootDir}/skills/` via `skillsjars:extract`. The existing `SkillFileWatcher` watches this directory with a 2-second debounce and triggers `skillLoader.reloadAll(true)` automatically. This requires zero code changes.

**GraalVM Native Image**: The `getResources()` call works for index-based resources. For JARs baked into the native image, we still need the build-time index approach (see Step 4).

#### Step 3: Add `allowed-tools` Mapping to `SkillDefinitionParser`

Update `SkillFrontmatter`:

```java
public record SkillFrontmatter(
    String name,
    String description,
    @JsonProperty("trigger-patterns") List<String> triggerPatterns,
    @JsonProperty("tool-capabilities") List<String> toolCapabilities,
    @JsonProperty("allowed-tools") String allowedTools,  // NEW
    @JsonProperty("max-iterations") int maxIterations) {}
```

Update `SkillDefinitionParser.parse()` to merge `allowed-tools` into `tool-capabilities`:

```java
List<String> mergedToolCapabilities = new ArrayList<>();
if (fm.toolCapabilities() != null) {
    mergedToolCapabilities.addAll(fm.toolCapabilities());
}
if (fm.allowedTools() != null && !fm.allowedTools().isBlank()) {
    mergedToolCapabilities.addAll(mapSkillsJarTools(fm.allowedTools()));
}
```

Tool mapping logic:

```java
private static final Map<String, String> SKILLSJARS_TOOL_MAP = Map.ofEntries(
    Map.entry("Bash", "SANDBOX_EXEC"),
    Map.entry("bash", "SANDBOX_EXEC"),
    Map.entry("shell", "SANDBOX_EXEC"),
    Map.entry("browser_navigate", "BROWSER_NAVIGATE"),
    Map.entry("github", "GITHUB"),
    Map.entry("garuda", "DELEGATE_TO_AGENT"),
    Map.entry("delegate", "DELEGATE_TO_AGENT")
    // Read, Edit, Write are implicit — no mapping needed
);

private List<String> mapSkillsJarTools(String allowedTools) {
    return Arrays.stream(allowedTools.split("\\s+"))
        .map(tool -> SKILLSJARS_TOOL_MAP.getOrDefault(tool, null))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
}
```

#### Step 4: Build-Time Index for GraalVM Native Image

For native image builds, `getResources("META-INF/skills")` won't work at runtime because JARs are baked into the binary. Two approaches:

**Approach A: Quarkus `BuildStep` (Recommended)**
Add a build step in the `deployment` module that scans `com.skillsjars:*` dependencies at build time and registers each `META-INF/skills/**/SKILL.md` path via `NativeImageResourceBuildItem`:

```java
@BuildStep
NativeImageResourceBuildItem registerSkillsJarResources(CombinedIndexBuildItem index) {
    // Scan all JARs for META-INF/skills/**/SKILL.md entries
    // Register each path as a native image resource
}
```

**Approach B: Build-time index generation**
Use `maven-antrun-plugin` to scan SkillsJars JARs and append their paths to `/skills/index.txt` during `process-resources`. The existing `ClasspathSkillProvider` reads this file, so the skills load normally in native image.

> **Do NOT use `pom.xml` additional-build-args** for this — Quarkus splits on commas breaking the arg (per AGENTS.md §8). Use `native-image.properties` or `NativeImageResourceBuildItem` instead.

#### Step 5: Add Security Configuration

In `SkillLoader.addSafe()`, check the source and apply approval requirement using an explicit sensitive capability set (not category-based):

```java
private static final Set<ToolCapability> SENSITIVE_CAPABILITIES = Set.of(
    ToolCapability.SANDBOX_EXEC,
    ToolCapability.BROWSER_NAVIGATE,
    ToolCapability.WEB_SEARCH_BRAVE,
    ToolCapability.WEB_SEARCH_SERPER,
    ToolCapability.DELEGATE_TO_AGENT
);

// In addSafe():
boolean isThirdParty = "skillsjars".equals(source);
boolean hasSensitiveOps = toolsResult.valid().stream()
    .anyMatch(SENSITIVE_CAPABILITIES::contains);
boolean requiresApproval = isThirdParty && hasSensitiveOps && config.requireApprovalForSensitive();

RoleDefinition roleDef = new RoleDefinition(
    canonicalRoleName,
    "Skill: " + def.name(),
    def.description(),
    def.maxIterations(),
    requiresApproval,  // NOW CONDITIONAL
    false,
    toolsResult.valid(),
    Set.of(def.name().toLowerCase(Locale.ROOT)));
```

> **Why not category-based?** The mapping between `ToolCapability.category()` and `ToolOperation` is not 1:1. `DELEGATE_TO_AGENT` is `INTERNAL` category but maps to `DELEGATE_AUTONOMOUS` (MEDIUM risk). `WEB_FETCH` is `NETWORK` but less sensitive than `BROWSER_NAVIGATE`. The explicit set matches AGENTS.md §2.9 — the same capabilities that `ToolGovernanceGuard` always gates regardless of role trust.

#### Step 6: Verification

```bash
# Compile check
./mvnw compile -q

# Run existing skill tests
./mvnw test -pl core -Dtest="SkillDefinitionParserTest,SkillLoaderTest,SkillRegistryTest"

# Verify CDI discovery (SkillsJars provider should appear in logs)
# Look for: "Loading skills from provider: skillsjars (priority=50)"
```

---

### Option B: Separate Module `amsha-skillsjars` (Alternative)

If isolation is preferred (e.g., to keep SkillsJars dependencies out of the core app):

1. Create `skillsjars/` module with `pom.xml` depending on `amsha-core`
2. Implement `SkillsJarSkillProvider` as `@ApplicationScoped` implementing `SkillProvider`
3. **CDI Discovery (CRITICAL — per AGENTS.md §4)**: Every non-app Maven module with CDI beans MUST include:
   - `jandex-maven-plugin` in `<build><plugins>` section
   - `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`
   
   Without these, the `@ApplicationScoped` bean is **silently invisible** — no error, no warning, just missing beans. Compilation + unit test pass in isolation is NOT sufficient proof.
4. Add `<module>skillsjars</module>` to root `pom.xml`
5. Users opt-in by adding `<dependency>` in `app/pom.xml`
6. Verify CDI discovery: grep startup logs for bean-init messages (e.g., `[SkillsJar] Enabled`)

**When to use this**: If SkillsJars integration is optional and shouldn't increase the default app binary size.

---

## 6. Verification Checklist

| Check | Command | Expected |
|---|---|---|
| Compilation | `./mvnw compile -q` | No errors |
| Skill parser tests | `./mvnw test -pl core -Dtest="SkillDefinitionParserTest"` | All pass |
| Skill loader tests | `./mvnw test -pl core -Dtest="SkillLoaderTest"` | All pass |
| CDI discovery | Grep startup logs for `skillsjars` provider | Provider registered |
| Native image | `mise build:native-local` | SkillsJars skills loaded via index |
| Tool mapping | Add test skill with `allowed-tools: Bash` | Maps to `SANDBOX_EXEC` |
| Fuzzy match gap | Add test skill with `allowed-tools: garuda` | Explicit mapping to `DELEGATE_TO_AGENT` (not fuzzy) |
| Security | Add SkillsJars skill with `SANDBOX_EXEC` | `requiresApproval=true` |
| Security bypass | Add SkillsJars skill with `GITHUB` only | `requiresApproval=false` (not sensitive) |
| Import alignment | `python3 ./fix_fqns.py && ./mvnw spotless:apply -q` | Clean |
| Spotless | `./mvnw spotless:check -q` | No violations |

---

## 7. Mermaid Diagram

```mermaid
graph TD
    A[SkillsJars JARs on Classpath] -->|META-INF/skills/**/SKILL.md| B[ClasspathSkillProvider]
    B -->|Parse YAML + Map Tools| C[SkillDefinitionParser]
    C -->|SkillDefinition| D[SkillLoader]
    D -->|Merge by Priority| E[SkillRegistry]
    D -->|registerDynamic| F[RoleRegistry]
    F -->|Delegatable via garuda| G[DelegateToAgentTool]

    W[skillsjars:extract] -->|{workspace}/skills/*.md| WF[SkillFileWatcher]
    WF -->|hot-reload 2s debounce| D

    H[Database SkillProvider] -->|Priority 10| D
    B -->|Priority 100| D
    B2[SkillsJarSkillProvider] -->|Priority 50| D

    style A fill:#f9f,stroke:#333
    style W fill:#f9f,stroke:#333
    style D fill:#bbf,stroke:#333
    style F fill:#bfb,stroke:#333
    style WF fill:#ff9,stroke:#333
```

---

## 8. Summary of Changes from Original Analysis

| Original Claim | Correction |
|---|---|
| "Option C: New module `amsha-skillsjars` with Hybrid Indexing [RECOMMENDED]" | Option A is simpler — extend existing `ClasspathSkillProvider`. New module only if isolation is needed. |
| "Build-Time Indexing: Maven plugin generates `META-INF/skills/skills-index.txt`" | `ClasspathSkillProvider` already uses `/skills/index.txt`. Extend it, don't create a new index. |
| Priority = 80 | Should be **50** (between Database=10 and Classpath=100) |
| Security: "Skills run within standard `ToolGovernanceGuard` limits" | **Wrong.** Skills get `requiresApproval=false` by default. SkillsJars skills with sensitive ops must have `requiresApproval=true`. |
| No mention of `SkillLoader` dynamic role registration | Skills automatically become agent roles via `roleRegistry.registerDynamic()`. This is already built. |
| No tool mapping table | Added comprehensive mapping from SkillsJars `allowed-tools` to Amsha `ToolCapability` |
| No mention of existing `ClasspathSkillProvider` index file | `core/src/main/resources/skills/index.txt` already exists and works in Native Image |
| Tool mapping uses `compileTools()` fuzzy match | **Wrong.** Fuzzy match won't handle `Bash`→`SANDBOX_EXEC` or `garuda`→`DELEGATE_TO_AGENT`. Explicit `SKILLSJARS_TOOL_MAP` needed in `SkillDefinitionParser`. |
| Security check uses `ToolCategory.COMPUTE \|\| ToolCategory.NETWORK` | **Imprecise.** Category-to-operation mapping is not 1:1. Use explicit `Set<ToolCapability>` matching AGENTS.md §2.9. |
| GraalVM: `maven-antrun-plugin` | **Incomplete.** Use Quarkus `BuildStep` + `NativeImageResourceBuildItem` instead. `antrun` alone won't register resources for native image. |
| No mention of `SkillFileWatcher` | Added as zero-effort integration pathway — extract to workspace `skills/` dir → hot-reload works automatically. |
| Option B CDI requirements | Expanded with explicit warning about silently invisible beans and verification steps. |
