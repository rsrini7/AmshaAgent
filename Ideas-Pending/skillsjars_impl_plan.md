# Implementation Plan: SkillsJars Integration (Final Revised)

This document outlines the step-by-step technical plan to integrate SkillsJars into the Amsha Agent framework by extending the existing `ClasspathSkillProvider` to scan classpath JAR dependencies using a build-time Quarkus `BuildStep`, mapping standard tools, and implementing necessary security controls.

---

## User Review Required

> [!WARNING]
> **Security Handshake (HITL Gating)**: SkillsJars loaded from Maven Central are third-party and untrusted. By default, dynamic roles resolved from skills run with `requiresApproval = false` under the `RESEARCHER` role.
> 
> To prevent unauthorized code execution, network requests, or agent delegation, we introduce the configuration property `amsha.skills.skillsjars.require-approval-for-sensitive` (default: `true`). When active:
> * Any third-party skill declaring sensitive capabilities (`SANDBOX_EXEC`, `BROWSER_NAVIGATE`, `WEB_SEARCH_BRAVE`, `WEB_SEARCH_SERPER`, or `DELEGATE_TO_AGENT`) will have `requiresApproval = true` set on its dynamic `RoleDefinition`.
> * Both `ToolInvocationGuard` and `ToolGovernanceGuard` will check the dynamic role's approval status directly using raw role strings, ensuring that sensitive operations require explicit human-in-the-loop (HITL) approval, bypassing the default static `RESEARCHER` role mapping.

---

## Proposed Changes

We will implement a hybrid build-time scanning approach (extending `ClasspathSkillProvider`) to load SkillsJars dynamically.

### 1. Developer Workflow & Database Conventions

* **No Flyway Migrations**: SkillsJars integration operates purely on the classpath/resource layer and does not touch the database. No database migrations (`db/migration/`) are needed.
* **Active Development**: During development, database migrations are skipped. Delete the `data/` directory to rebuild databases from seed data instead of writing manual rollback scripts.

---

### 2. Maven Configuration

#### [MODIFY] [app/pom.xml](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/app/pom.xml)
Add baseline SkillsJars dependency to the runtime classpath of the application:
```xml
<dependency>
    <groupId>com.skillsjars</groupId>
    <artifactId>browser-use__browser-use__remote-browser</artifactId>
    <version>2026_02_23-1d154e1</version>
</dependency>
```

---

### 3. Build-Time Resource Generation & Native Image Registration

#### [NEW] [SkillsJarBuildStep.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/deployment/src/main/java/com/amsha/deployment/SkillsJarBuildStep.java)
Implement a Quarkus build step to scan dependencies at build-time (in JVM mode), register resources for Native Image compilation, and output a dynamic index:

> [!NOTE]
> **Classloader Note**: This build step scans resources using `Thread.currentThread().getContextClassLoader()`, which refers to the Quarkus deployment classloader during curation/augmentation. Because all dependency JARs (including `com.skillsjars:*`) are on the build classpath, this resolver discovers them. If dynamic classloader boundaries cause lookup misses in specific profiles, we will verify and fallback to using Quarkus `ApplicationArchivesBuildItem` to retrieve paths from curation outcomes.

```java
package com.amsha.deployment;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import org.jboss.logging.Logger;

public class SkillsJarBuildStep {

    private static final Logger LOG = Logger.getLogger(SkillsJarBuildStep.class);
    private static final String SKILLS_DIR = "META-INF/skills";
    private static final String INDEX_PATH = "META-INF/skills/skills-index.txt";

    @BuildStep
    void discoverAndRegisterSkills(
            BuildProducer<NativeImageResourceBuildItem> nativeResources,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {
        
        List<String> skillPaths = new ArrayList<>();
        try {
            Enumeration<URL> resources = Thread.currentThread()
                    .getContextClassLoader()
                    .getResources(SKILLS_DIR);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                scanDirectory(url, skillPaths, nativeResources);
            }
        } catch (IOException e) {
            LOG.warnf("Failed to discover SkillsJars at build-time: %s", e.getMessage());
        }

        if (!skillPaths.isEmpty()) {
            LOG.infof("Discovered %d SkillsJars skill(s) at build-time", skillPaths.size());
            String indexContent = String.join("\n", skillPaths);
            generatedResources.produce(new GeneratedResourceBuildItem(INDEX_PATH, indexContent.getBytes(StandardCharsets.UTF_8)));
            nativeResources.produce(new NativeImageResourceBuildItem(INDEX_PATH));
        }
    }

    private void scanDirectory(
            URL url,
            List<String> skillPaths,
            BuildProducer<NativeImageResourceBuildItem> nativeResources) {
        try {
            String protocol = url.getProtocol();
            if ("jar".equals(protocol)) {
                URLConnection conn = url.openConnection();
                if (conn instanceof JarURLConnection jarConn) {
                    try (JarFile jarFile = jarConn.getJarFile()) {
                        Enumeration<JarEntry> entries = jarFile.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith(SKILLS_DIR + "/") && name.endsWith("/SKILL.md")) {
                                skillPaths.add(name);
                                nativeResources.produce(new NativeImageResourceBuildItem(name));
                                LOG.debugf("Build-time registered SkillsJars resource: %s", name);
                            }
                        }
                    }
                }
            } else if ("file".equals(protocol)) {
                Path dir = Path.of(url.toURI());
                try (Stream<Path> paths = Files.walk(dir)) {
                    paths.filter(p -> p.toString().endsWith("SKILL.md"))
                            .forEach(p -> {
                                String pathStr = p.toAbsolutePath().toString().replace("\\", "/");
                                int idx = pathStr.indexOf(SKILLS_DIR);
                                if (idx != -1) {
                                    String relative = pathStr.substring(idx);
                                    skillPaths.add(relative);
                                    nativeResources.produce(new NativeImageResourceBuildItem(relative));
                                    LOG.debugf("Build-time registered local skill resource: %s", relative);
                                }
                            });
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to scan directory %s for skills: %s", url, e.getMessage());
        }
    }
}
```

---

### 4. Dynamic Scanning & Loading

#### [MODIFY] [ClasspathSkillProvider.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/skill/ClasspathSkillProvider.java)
Modify `ClasspathSkillProvider` to load **both** index files:
1. Baseline local classpath skills from `/skills/index.txt` (using `/skills/` prefix mapping).
2. Build-time generated SkillsJars dependencies from `META-INF/skills/skills-index.txt` (using raw/absolute path resolution).

**Deduplication & Collisions**: Any duplicate skill names loaded from both providers are deterministically resolved by `SkillLoader` during the priority-ordered merge. Because local files and classpath baseline skills have lower priority (`100`), they will be overridden by SkillsJars (priority `50`/`80`) or user database records (priority `10`). Within the classpath loader itself, later discovered items overwrite previous ones.

Update implementation to support absolute classpath lookup:
```java
    @Override
    public List<SkillDefinition> load() {
        List<SkillDefinition> skills = new ArrayList<>();
        
        // 1. Load local baseline skills from /skills/index.txt
        loadFromIndex(INDEX_PATH, true, skills);

        // 2. Load SkillsJars skills using the build-time generated index
        loadFromIndex(SKILLSJARS_INDEX_PATH, false, skills);

        return skills;
    }

    private void loadFromIndex(String indexPath, boolean prependSkillsPrefix, List<SkillDefinition> skills) {
        InputStream indexStream = indexPath.startsWith("/") 
                ? getClass().getResourceAsStream(indexPath)
                : Thread.currentThread().getContextClassLoader().getResourceAsStream(indexPath);

        if (indexStream == null) {
            LOG.debugf("Classpath index %s missing. Skipping.", indexPath);
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String resourcePath = prependSkillsPrefix ? line : line;
                loadSkillFromResource(resourcePath, prependSkillsPrefix, skills);
            }
        } catch (Exception e) {
            LOG.errorf("Failure reading classpath skill index %s: %s", indexPath, LogUtil.rootMessage(e));
        }
    }

    private void loadSkillFromResource(String resourcePath, boolean prependSkillsPrefix, List<SkillDefinition> skills) {
        String classpathPath = prependSkillsPrefix
                ? "/skills/" + resourcePath
                : "/" + resourcePath;
        try (InputStream stream = getClass().getResourceAsStream(classpathPath)) {
            if (stream != null) {
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                skills.add(parser.parse(content));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to read skill at %s: %s", classpathPath, e.getMessage());
        }
    }
```

---

### 5. Frontmatter & Tool Mapping

#### [MODIFY] [SkillFrontmatter.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/skill/SkillFrontmatter.java)
Expose the optional `allowed-tools` field:
```java
public record SkillFrontmatter(
        String name,
        String description,
        @JsonProperty("trigger-patterns") List<String> triggerPatterns,
        @JsonProperty("tool-capabilities") List<String> toolCapabilities,
        @JsonProperty("allowed-tools") String allowedTools, // NEW
        @JsonProperty("max-iterations") int maxIterations) {}
```

#### [MODIFY] [SkillDefinitionParser.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/skill/SkillDefinitionParser.java)
Integrate `ToolCapability.fromToolName` direct matching and alias fallbacks:

> [!NOTE]
> **Case Normalization**: `mapSkillsJarTools()` calls `tool.toLowerCase(Locale.ROOT)` before looking up in the tool map, ensuring that variations like `Bash`, `bash`, or `BASH` match the lowercase keys in the map.

```java
private static final Map<String, String> SKILLSJARS_TOOL_MAP = Map.ofEntries(
        Map.entry("bash", "SANDBOX_EXEC"),
        Map.entry("shell", "SANDBOX_EXEC"),
        Map.entry("cmd", "SANDBOX_EXEC"),
        Map.entry("browser_navigate", "BROWSER_NAVIGATE"),
        Map.entry("github", "GITHUB"),
        Map.entry("web_search", "WEB_SEARCH_BRAVE"), // Config default
        Map.entry("garuda", "DELEGATE_TO_AGENT"),
        Map.entry("delegate", "DELEGATE_TO_AGENT")
);

private List<String> mapSkillsJarTools(String allowedTools) {
    if (allowedTools == null || allowedTools.isBlank()) {
        return List.of();
    }
    return Arrays.stream(allowedTools.split("\\s+"))
            .map(tool -> {
                var cap = ToolCapability.fromToolName(tool);
                if (cap.isPresent()) {
                    return cap.get().name();
                }
                return SKILLSJARS_TOOL_MAP.get(tool.toLowerCase(Locale.ROOT));
            })
            .filter(Objects::nonNull)
            .distinct()
            .toList();
}
```

---

### 6. Security, Capability Registry, & Trust Flow Verification

#### [MODIFY] [CapabilityRegistry.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/agent/role/CapabilityRegistry.java)
Add a remove method to support dynamic role cleanup during reload:
```java
    public void remove(String roleId) {
        roleSupplementalTools.remove(roleId);
    }
```

#### [MODIFY] [SkillLoader.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/skill/SkillLoader.java)
Inject the configuration property and `CapabilityRegistry`. Register dynamic capabilities at load time, and clean them up during dynamic reloads.

> [!IMPORTANT]
> **Dynamic Capability Registration (CRITICAL)**: In `SkillLoader.addSafe()`, dynamic skill capabilities must be explicitly registered into the `CapabilityRegistry` by calling `capabilityRegistry.grant(canonicalRoleName, cap.toolName())` for each validated capability. Without this registration, `verifyRoleCapability()` in `ToolGovernanceGuard` will block all tool calls for dynamic roles, even if the approval check passes.

> [!NOTE]
> **Dynamic Security Detection**: To determine if a skill requires manual approval, we leverage the existing `source` parameter passed to `addSafe()`. If `source` equals `"skillsjars"`, we check if the skill declares any sensitive capabilities using a hybrid category-and-enum check (`ToolCategory.COMPUTE || ToolCategory.NETWORK || ToolCapability.DELEGATE_TO_AGENT`). This approach dynamically scales with new compute or network capabilities while explicitly gating delegation.

```java
   @Inject
   @ConfigProperty(name = "amsha.skills.skillsjars.require-approval-for-sensitive", defaultValue = "true")
   boolean requireApprovalForSensitive;
                                                                                                                                                                                                                                                                                                            
   @Inject
   CapabilityRegistry capabilityRegistry;

   // Inside reloadAll() before mapping new skills:
   for (String roleName : roleRegistry.getDynamicRoles().stream().map(RoleDefinition::canonicalName).toList()) {
       capabilityRegistry.remove(roleName);
   }

   // Inside addSafe() after creating roleDef:
   boolean isThirdParty = "skillsjars".equals(source);
   boolean hasSensitiveOps = toolsResult.valid().stream()
           .anyMatch(cap -> cap.category() == ToolCategory.COMPUTE
                         || cap.category() == ToolCategory.NETWORK
                         || cap == ToolCapability.DELEGATE_TO_AGENT);
   boolean requiresApproval = isThirdParty && hasSensitiveOps && requireApprovalForSensitive;

   RoleDefinition roleDef = new RoleDefinition(
           canonicalRoleName,
           "Skill: " + def.name(),
           def.description(),
           def.maxIterations(),
           requiresApproval,
           false,
           toolsResult.valid(),
           Set.of(def.name().toLowerCase(Locale.ROOT)));

   for (ToolCapability cap : toolsResult.valid()) {
       capabilityRegistry.grant(canonicalRoleName, cap.toolName());
   }
```

#### [MODIFY] [RoleRegistry.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/agent/role/RoleRegistry.java)
Add a string-based dynamic lookup method that intercepts dynamic role checks:

> [!IMPORTANT]
> **Why String-Based Lookups?**: `AgentRoleEnum.fromString()` maps all dynamic skill roles (IDs starting with `"skill:"`) to the static `RESEARCHER` fallback. This means checking `roleRegistry.requiresApprovalForTools(resolvedRole)` evaluates the static `RESEARCHER` settings and ignores the custom registered `requiresApproval` setting of the dynamic role. String-based lookups allow us to bypass this fallback and check the dynamic role registry first.

```java
public boolean requiresApprovalForTools(String roleName) {
    if (roleName == null) return true;
    RoleDefinition dynamicDef = dynamicRoles.get(roleName);
    if (dynamicDef != null) {
        return dynamicDef.requiresApproval();
    }
    try {
        AgentRoleEnum role = AgentRoleEnum.fromString(roleName);
        return role.definition().requiresApproval();
    } catch (IllegalArgumentException e) {
        return true;
    }
}
```

#### [MODIFY] [ToolInvocationGuard.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/agent/loop/ToolInvocationGuard.java)
Update to verify approval by passing the raw `agentRole` string to `RoleRegistry`:
```java
// Prevents silent fallback mapping to RESEARCHER from overriding dynamic requiresApproval checks
if ((roleRegistry.requiresApprovalForTools(agentRole) || toolRequiresApproval)
```

#### [MODIFY] [ToolGovernanceGuard.java](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/core/src/main/java/com/amsha/core/tools/ToolGovernanceGuard.java)
Ensure dynamic governance matches raw context roles:
```java
boolean isTrustedRole = !roleRegistry.requiresApprovalForTools(ctx.agentRole());
```

---

### 7. Configuration Updates

#### [MODIFY] [application.yaml](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/app/src/main/resources/application.yaml)
Add property under `amsha.skills`:
```yaml
  skills:
    hot-reload: true
    skillsjars:
      require-approval-for-sensitive: ${AMSHA_SKILLSJARS_REQUIRE_APPROVAL_FOR_SENSITIVE:true}
```

#### [MODIFY] [.env.example](file:///Users/srinivasanragothaman/ws/AmshaAgentInternal/.env.example)
Add environment variable placeholder:
```bash
# Set to true to require Human-in-the-Loop approval for sensitive third-party SkillsJars capabilities
AMSHA_SKILLSJARS_REQUIRE_APPROVAL_FOR_SENSITIVE=true
```

---

### 8. Dynamic Intent Limitation Note

SkillsJars skills do not declare `trigger-patterns` frontmatter. As a result:
* They **will not** auto-route via `SkillAwareIntentDetector`.
* They are only accessible via explicit delegation (`DelegateToAgentTool` / `garuda`), the `/skills` reflex command listing, or direct coordinator selection.

*Note on SkillFileWatcher*: Extracting SkillsJars `.md` files to the `{workspaceRootDir}/skills/` folder via `skillsjars:extract` remains supported out of the box as a zero-effort hot-reload pathway managed by `SkillFileWatcher`.

---

## Verification Plan

### Automated Verification
* Align and clean up imports + styling rules (per **AGENTS.md §1**):
  ```bash
  python3 ./fix_fqns.py && ./mvnw spotless:apply -q
  ```
* Run spotless verification check:
  ```bash
  ./mvnw spotless:check -q
  ```
* Run compilation:
  ```bash
  ./mvnw clean compile -q
  ```
* Run existing tests:
  ```bash
  ./mvnw test -pl core -Dtest="SkillDefinitionParserTest,SkillLoaderTest,SkillRegistryTest"
  ```
* Run local native compilation verification (per **AGENTS.md §8**):
  ```bash
  mise run build:native-local
  ```
* Add unit tests verifying:
  1. `allowed-tools` maps correctly to `tool-capabilities` (both direct enum lookups and alias fallbacks).
  2. SkillsJars-sourced skills with sensitive capabilities default to `requiresApproval = true` when configuration is enabled.
  3. String-based lookup in `RoleRegistry` resolves dynamic dynamic roles correctly.
  4. CapabilityRegistry contains the dynamically granted capabilities of dynamic skills, and they are cleared during reload.
