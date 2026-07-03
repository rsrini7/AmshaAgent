## SkillsJars is NOT a Quarkus Extension

[skillsjars.com](https://www.skillsjars.com/) is a **Maven Central registry for AI Agent Skill definitions** (analogous to WebJars but for agent skills). It packages `.md` skill files (following the [Agent Skills spec](https://agentskills.io/specification)) into JARs. It has **zero relationship** to `quarkus-langchain4j` or any Quarkus extension. There is no `quarkus-langchain4j-skills` extension, no `SkillsToolProvider` CDI bean, etc.

## What SkillsJars Actually Is

Skills are packaged as Maven dependencies under the `com.skillsjars` groupId.

### 1. Naming & Coordinate Conventions
The artifactId is constructed from the GitHub organization, repository, and skill path joined with `__` (all lowercase, with non-alphanumeric characters except `-` and `_` removed):
- **Standard Skills**: For a skill located at `skills/myskill/SKILL.md` in `myorg/myrepo`, the coordinates are:
  `com.skillsjars:myorg__myrepo__myskill:<version>`
- **Root-level Skills**: If the repository has a root-level `SKILL.md` (and no `skills/` directory), the artifactId is just `org__repo`:
  `com.skillsjars:org__repo:<version>`
- **Nested Skills**: For nested paths like `skills/skillgroup/myskill/SKILL.md`, it resolves to:
  `com.skillsjars:myorg__myrepo__skillgroup__myskill:<version>`

### 2. Jar Structure
Inside the JAR, the skill files are placed at a well-known path:
```
/META-INF/skills/<org>/<repo>/<skill-name>/SKILL.md
```
Any additional helper files (e.g., Python scripts, JSON configuration, or text assets) located in the skill's source directory are packaged alongside `SKILL.md` in the same directory.

### 3. Allowed Tools & Metadata
Each `SKILL.md` contains YAML frontmatter with metadata (`name`, `description`, optional `allowed-tools` which is a space-delimited list of pre-approved tools, and `license`).
- When published via SkillsJars.com, the `allowed-tools` list is automatically compiled into the JAR's POM as a Maven property:
  ```xml
  <properties>
      <skillsjars.skill.my-skill.allowed-tools>Bash Read Edit</skillsjars.skill.my-skill.allowed-tools>
  </properties>
  ```
- This allows client applications or orchestrators to query a skill's required tools directly from the POM/model without needing to unpack the JAR.

---

## How It Integrates

### 1. Build-Time Extraction (For AI Code Assistants & Filesystem Tools)
Since most desktop AI tools (like Cursor, Kiro, Claude) expect raw files on the filesystem, you add the plugin and list the skill JARs as dependencies of the plugin itself (to avoid polluting runtime dependencies).

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

- **Spring AI**: The `spring-ai-agent-utils` library provides native integration.
  Configure in `application.properties`:
  ```properties
  agent.skills.paths=classpath:/META-INF/skills
  ```
  And build the `SkillsTool`:
  ```java
  @Value("${agent.skills.paths}") List<Resource> skillPaths;
  ChatClient chatClient = chatClientBuilder
          .defaultToolCallbacks(
                  SkillsTool.builder().addSkillsResources(skillPaths).build()
          )
          .build();
  ```

---

## What This Means for Amsha Agent

Amsha Agent does not have native Spring AI dependencies, but it does run on Quarkus with a CDI-based architecture. To consume SkillsJars, we have two primary implementation pathways:

1. **Option A: Build-Time Extraction & Classpath Retrieval**
   Use the `skillsjars:extract` plugin in `pom.xml` to extract skill files into `src/main/resources/skills/` during the build lifecycle. The existing `ClasspathSkillProvider` in Amsha Agent can then load them as normal markdown files.
   
2. **Option B: CDI-Powered Classpath Scanning (Preferred)**
   Create a custom CDI bean, e.g., `SkillsJarClasspathProvider`, that implements the `SkillProvider` interface. This provider will:
   - Scan the classpath for resources matching `META-INF/skills/**/SKILL.md` (using Quarkus/JVM resource resolvers).
   - Parse each skill directly from the classpath at startup.
   - Register them with Amsha's `SkillLoader` pipeline.

This classpath-scanning approach provides a seamless, zero-extraction developer experience.

> [!WARNING]
> **Security Guardrails**: Since SkillsJars are downloaded from Maven Central and contain plain-text instructions that guide LLM actions, they should be vetted. Amsha Agent's tool governance (e.g., `ToolGovernanceGuard`) must remain active to monitor any `allowed-tools` declarations and prevent hijacked skills from invoking unauthorized sensitive tools.