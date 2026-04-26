## SkillsJars is NOT a Quarkus Extension

[skillsjars.com](https://www.skillsjars.com/) is a **Maven Central registry for AI Agent Skill definitions** - it packages `.md` skill files (following the [Agent Skills spec](https://agentskills.io/specification)) into JARs.  It has **zero relationship** to `quarkus-langchain4j` or any Quarkus extension. I fabricated that entire integration story - there is no `quarkus-langchain4j-skills` extension, no `SkillsToolProvider` CDI bean, none of it. Apologies for the confusion. 

## What SkillsJars Actually Is

Skills live inside JARs at a well-known path: 

```
META-INF/skills/<org>/<repo>/<skill-name>/SKILL.md
```

Each `SKILL.md` has YAML frontmatter (`name`, `description`, `allowed-tools`) followed by the skill instructions in Markdown.

Your dependency is exactly right - `browser-use__browser-use__remote-browser` is the **remote-browser** skill from the `browser-use` org: 

```xml
<dependency>
    <groupId>com.skillsjars</groupId>
    <artifactId>browser-use__browser-use__remote-browser</artifactId>
    <version>2026_02_23-1d154e1</version>
</dependency>
```

## How It Actually Integrates

The only **framework-native integration** documented is **Spring AI** via `spring-ai-agent-utils` - it provides a `SkillsTool` that reads skills directly off the classpath at `META-INF/skills`.  For everything else (AI code assistants like Kiro, Cursor, Claude), you use the **Maven plugin to extract** skill files to a directory: 

```bash
./mvnw skillsjars:extract -Ddir=.kiro/skills
```

## What This Means for ArcClaw

SkillsJars has **no native Quarkus/LangChain4j integration**. For ArcClaw, the practical options are:

1. **Use the extraction plugin** - extract `SKILL.md` files to `src/main/resources/skills/` at build time, and your existing `ClasspathSkillProvider` picks them up natively (since it already reads `.md` files with YAML frontmatter from the classpath)
2. **Read directly from classpath** - since the skill JARs are on the classpath, write a new `SkillsJarClasspathProvider` that scans `META-INF/skills/**/SKILL.md` resources and feeds them into `SkillLoader` - no extraction step, pure classpath scanning

Option 2 is the cleaner fit for ArcClaw's CDI architecture. Would you like me to design that `SkillsJarClasspathProvider` implementation specifically for ArcClaw's existing `SkillLoader` pipeline?