# SkillsJars Reference

## SkillsJars is NOT a Quarkus Extension

[skillsjars.com](https://www.skillsjars.com/) is a **Maven Central registry for AI Agent Skill definitions** (analogous to WebJars but for agent skills). It packages `.md` skill files (following the [Agent Skills spec](https://agentskills.io/specification)) into JARs. It has **zero relationship** to `quarkus-langchain4j` or any Quarkus extension. There is no `quarkus-langchain4j-skills` extension, no `SkillsToolProvider` CDI bean, etc.

---

## What SkillsJars Actually Is

Skills are packaged as Maven dependencies under the `com.skillsjars` groupId.

### 1. Naming & Coordinate Conventions

The artifactId is constructed from the GitHub organization, repository, and skill path joined by `__` (all lowercase, non-alphanumeric characters except `-` and `_` removed):

- **Standard Skills**: If the skill is located at `skills/myskill/SKILL.md` in `myorg/myrepo`, coordinates are:
  `com.skillsjars:myorg__myrepo__myskill:<version>`

- **Root-level Skills**: If the repository has a root-level `SKILL.md` (no `skills/` directory), artifactId is just `org__repo`:
  `com.skillsjars:org__repo:<version>`

- **Nested Skills**: Nested paths like `skills/skillgroup/myskill/SKILL.md` resolve to:
  `com.skillsjars:myorg__myrepo__skillgroup__myskill:<version>`

### 2. JAR Structure

Inside the JAR, skill files are placed at a well-known path:

```
/META-INF/skills/<org>/<repo>/<skill-name>/SKILL.md
```

Any additional helper files (e.g., Python scripts, JSON configuration, text assets) located in the skill's source directory are packaged alongside `SKILL.md` in the same directory.

### 3. Allowed Tools & Metadata

Each `SKILL.md` contains YAML frontmatter metadata:
- `name` — skill name
- `description` — human-readable description
- `allowed-tools` — optional space-delimited list of pre-approved tools
- `license` — SPDX license identifier

When published via SkillsJars.com, the `allowed-tools` list is automatically compiled into the JAR's POM as a Maven property:

```xml
<properties>
    <skillsjars.skill.my-skill.allowed-tools>Bash Read Edit</skillsjars.skill.my-skill.allowed-tools>
</properties>
```

This allows client applications and orchestrators to query a skill's required tools directly from the POM/model without needing to unpack the JAR.

---

## How SkillsJars Integrates

### 1. Build-Time Extraction (For AI Code Assistants & Filesystem Tools)

Since most desktop AI tools (like Cursor, Kiro, Claude) expect raw files on the filesystem, you add the plugin and list skill JARs as dependencies of the plugin itself (to avoid polluting runtime dependencies).

#### Maven Setup

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.skillsjars</groupId>
            <artifactId>maven-plugin</artifactId>
            <version>0.0.6</version>
            <dependencies>
                <dependency>
                    <groupId>com.skillsjars</groupId>
                    <artifactId>browser-use__browser-use__remote-browser</artifactId>
                    <version>2026_02_23-1d154e1</version>
                </dependency>
            </dependencies>
        </plugin>
    </plugins>
</build>
```

Run extraction to a target directory:

```bash
./mvnw skillsjars:extract -Ddir=.kiro/skills
```

#### Gradle Setup

```groovy
plugins {
    id("com.skillsjars.gradle-plugin") version "0.0.2"
}
```

Run extraction:

```bash
./gradlew extractSkillsJars -Pdir=.kiro/skills
```

### 2. Runtime Classpath Loading (For Custom Java/Spring AI Agents)

Custom agents running on the JVM can read skills directly off the classpath at runtime.

- **Spring AI**: The `spring-ai-agent-utils` library provides native integration. Configure in `application.properties`:

```properties
agent.skills.paths=classpath:/META-INF/skills
```

And build `SkillsTool`:

```java
@Value("${agent.skills.paths}")
List<Resource> skillPaths;

ChatClient chatClient = chatClientBuilder
    .defaultToolCallbacks(
        SkillsTool.builder().addSkillsResources(skillPaths).build()
    )
    .build();
```

---

## What This Means for Amsha Agent

Amsha Agent does not use Spring AI dependencies — it runs on a Quarkus CDI-based architecture. However, **Amsha Agent already has a mature skill infrastructure** that can consume SkillsJars with minimal changes.

> **Dev Workflow Note**: During active development, DB data migrations are not needed — delete the `data/` folder instead of running Flyway migrations. SkillsJars integration does not touch the database layer.

### Existing Infrastructure (Already Built)

| Component | File | What It Does |
|---|---|---|
| `SkillProvider` SPI | `core/.../skill/SkillProvider.java` | Interface for pluggable skill sources (`load()`, `sourceName()`, `priority()`) |
| `ClasspathSkillProvider` | `core/.../skill/ClasspathSkillProvider.java` | Loads skills from `/skills/index.txt` on classpath. **Already GraalVM Native Image compatible.** |
| `DatabaseSkillProvider` | `core/.../skill/DatabaseSkillProvider.java` | Loads skills from database. Priority = 10 (highest). |
| `SkillLoader` | `core/.../skill/SkillLoader.java` | Orchestrator. Iterates `SkillProvider` beans, merges by priority, compiles patterns, **registers dynamic agent roles** via `roleRegistry.registerDynamic()`. |
| `SkillDefinitionParser` | `core/.../skill/SkillDefinitionParser.java` | Parses YAML frontmatter + prompt body. Uses Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false` (tolerant of extra SkillsJars fields like `license`). |
| `SkillFrontmatter` | `core/.../skill/SkillFrontmatter.java` | Jackson DTO: `name`, `description`, `trigger-patterns`, `tool-capabilities`, `max-iterations`. |
| `SkillRegistry` | `core/.../skill/SkillRegistry.java` | In-memory registry of `CompiledSkillEntry` objects. |
| `SkillResource` | `app/.../skill/SkillResource.java` | REST API: `GET /skills`, `POST /skills`, `DELETE /skills/{name}`, `GET /skills/refresh`. |
| `SkillFileWatcher` | `core/.../skill/SkillFileWatcher.java` | Watches `{workspace}/skills/` for `.md` changes and hot-reloads via `skillLoader.reloadAll(true)`. Extracted SkillsJars files placed here get automatic hot-reload. |
| `index.txt` | `core/src/main/resources/skills/index.txt` | Static index file for `ClasspathSkillProvider`. Currently empty. |

### Integration Pathways

1. **Option A: Workspace Hot-Reload via `SkillFileWatcher` (Zero Code Changes)**
   Use `skillsjars:extract` to extract `.md` files into `{workspaceRootDir}/skills/`. The existing `SkillFileWatcher` watches this directory with a 2-second debounce and triggers `skillLoader.reloadAll(true)` on any `.md` change. **No build dependency, no code changes, hot-reload works automatically.** Note: `ClasspathSkillProvider` reads from `/skills/index.txt` on the classpath (a different path), so extracted files are loaded by the file watcher, not the classpath provider.

2. **Option B: Build-Time Extraction & Classpath Retrieval**
   Use the `skillsjars:extract` plugin in `pom.xml` to extract skill files into `src/main/resources/skills/` during the build lifecycle. The existing `ClasspathSkillProvider` will load them as normal markdown files. **Zero code changes, but requires build-time extraction.**

3. **Option C: Extend `ClasspathSkillProvider` to Scan `META-INF/skills/`**
   Modify `ClasspathSkillProvider.load()` to also scan for `META-INF/skills/**/SKILL.md` entries from SkillsJars JARs on the classpath. This provides seamless, zero-extraction developer experience. Requires handling both `jar:` and `file:` protocol URLs when scanning resources.

4. **Option D: Separate `SkillsJarSkillProvider` (For Isolation)**
   Create a new `SkillProvider` implementation in a separate module (`amsha-skillsjars`) with priority = 50 (between Database=10 and Classpath=100). Users opt-in by adding the dependency. **Requires CDI discovery setup**: `jandex-maven-plugin` in `<build><plugins>` + `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"` (per AGENTS.md §4). Without these, the `@ApplicationScoped` bean is silently invisible.

### What Needs to Change

| Change | Scope | Effort |
|---|---|---|
| Add `allowed-tools` field to `SkillFrontmatter` | `core/.../skill/SkillFrontmatter.java` | Small |
| Add tool mapping logic to `SkillDefinitionParser` | `core/.../skill/SkillDefinitionParser.java` | Small |
| Extend `ClasspathSkillProvider` to scan `META-INF/skills/` | `core/.../skill/ClasspathSkillProvider.java` | Small |
| Add security config for third-party skill approval | `core/.../skill/SkillLoader.java` | Small |
| Add SkillsJars dependencies to `app/pom.xml` | `app/pom.xml` | Trivial |
| Build-time index generation for GraalVM Native | Quarkus `BuildStep` or `NativeImageResourceBuildItem` | Medium | Register `META-INF/skills/**/SKILL.md` paths at build time. `antrun` plugin alone won't work — Quarkus splits on commas breaking args. |

### Tool Name Mapping

SkillsJars `allowed-tools` uses human-readable names. Amsha uses `ToolCapability` enum values. The existing `SkillLoader.compileTools()` uses a fuzzy match (uppercase + strip underscores), but this **won't handle SkillsJars names** — an explicit mapping function in `SkillDefinitionParser` is required.

| SkillsJars `allowed-tools` | Amsha `ToolCapability` | Fuzzy Match? | Notes |
|---|---|---|---|
| `Bash` | `SANDBOX_EXEC` | **No** (`BASH` ≠ `SANDBOXEXEC`) | Explicit mapping needed |
| `Read` | *(implicit)* | N/A | File reading has no tool gate |
| `Edit` | *(implicit)* | N/A | File editing has no tool gate |
| `Write` | *(implicit)* | N/A | File writing has no tool gate |
| `browser_navigate` | `BROWSER_NAVIGATE` | Yes | `BROWSERNAVIGATE` = `BROWSERNAVIGATE` |
| `github` | `GITHUB` | Yes | Exact match |
| `web_search` | `WEB_SEARCH_BRAVE` or `WEB_SEARCH_SERPER` | No | Explicit mapping to whichever is configured |
| `garuda` / `delegate` | `DELEGATE_TO_AGENT` | **No** (`GARUDA` ≠ `DELEGATETOAGENT`) | Explicit mapping needed (note: `toolName` is `"garuda"`, not the enum name) |

> [!WARNING]
> **Security Guardrails**: SkillsJars skills are community-contributed and downloaded from Maven Central. They contain plain-text instructions that guide LLM actions.
>
> **Critical gap**: In `SkillLoader.addSafe()`, all skills get `requiresApproval = false` by default. This means a SkillsJars skill declaring `allowed-tools: Bash` (mapped to `SANDBOX_EXEC`) can execute code **without human-in-the-loop approval**.
>
> **Mitigation**: Add a config property `amsha.skills.skillsjars.require-approval-for-sensitive=true` that sets `requiresApproval=true` for SkillsJars-sourced skills declaring sensitive tool capabilities. The sensitive set should match AGENTS.md §2.9 (`ToolGovernanceGuard` always requires HITL for these regardless of role trust):
> - `SANDBOX_EXEC` (maps to `EXECUTE_SHELL` operation — CRITICAL risk)
> - `BROWSER_NAVIGATE` (maps to `NETWORK_OUTBOUND` operation)
> - `WEB_SEARCH_BRAVE` (maps to `NETWORK_OUTBOUND` operation)
> - `WEB_SEARCH_SERPER` (maps to `NETWORK_OUTBOUND` operation)
> - `DELEGATE_TO_AGENT` (maps to `DELEGATE_AUTONOMOUS` operation — MEDIUM risk)
>
> Do **not** use a category-based check (`ToolCategory.COMPUTE || ToolCategory.NETWORK`) — the mapping between `ToolCapability.category()` and `ToolOperation` is not 1:1. Use an explicit `Set<ToolCapability>` instead.
>
> See `analysis_skillsjars.md` §4 for the full security model.

---

## See Also

- [analysis_skillsjars.md](./analysis_skillsjars.md) — Full integration analysis with implementation plan
- [agentskills.io specification](https://agentskills.io/specification) — The open standard SkillsJars implements
- [skillsjars.com](https://www.skillsjars.com/) — SkillsJars registry
