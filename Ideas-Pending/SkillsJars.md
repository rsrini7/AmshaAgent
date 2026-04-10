Gemini's summary is **mostly accurate but has a few important gaps and one misleading point** worth clarifying — especially given your ArcClaw architecture.

***

## What's Accurate in Gemini's Answer

- The `quarkus-langchain4j-skills` extension does exist, registers a `SkillsToolProvider` CDI bean, and is configured via `quarkus.langchain4j.skills.directories` [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/skills.html)
- Skills are loaded from a directory structure where each subdirectory is a skill containing a `SKILL.md` file with YAML front-matter [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/skills.html)
- The SkillsJars Maven/Gradle plugin extracts skills from JAR dependencies onto the filesystem [skillsjars](https://www.skillsjars.com/docs)

***

## What Gemini Got Wrong or Missed

**1 — The Quarkus extension reads from the filesystem, NOT the classpath JAR directly**

Unlike Spring AI (which reads from `classpath:/META-INF/skills`), the Quarkus extension currently only supports loading from a **filesystem directory** via `quarkus.langchain4j.skills.directories`. This means you still need the extraction step even for Quarkus runtime use, not just for IDE/code assistants. [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/skills.html)

**2 — There is no official Quarkus-specific Maven plugin from SkillsJars**

The SkillsJars plugin is a general-purpose extractor. The target directory depends on what consumes it. For Quarkus, you extract to wherever you point `quarkus.langchain4j.skills.directories`, not a hardcoded `bob/` folder as Gemini implied. [skillsjars](https://www.skillsjars.com/docs)

**3 — Shell skills are NOT supported in Quarkus integration yet**

Only **tool mode** skills work with the extension. Shell-mode skills have no Quarkus integration — you'd need to use `langchain4j-experimental-skills-shell` directly. [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/skills.html)

**4 — `github.com/quarkusio/quarkus-skills` is misidentified**

Gemini cited this as a "direct assistant integration" repo. It does not exist — the real repo is `quarkiverse/quarkus-langchain4j`. [github](https://github.com/quarkiverse/quarkus-langchain4j)

***

## How This Fits ArcClaw

Given ArcClaw's architecture, here's the practical integration flow for you:

```
pom.xml
├── dependency: com.skillsjars:some-skill:version          ← packaged skill JAR
└── plugin: com.skillsjars:maven-plugin                    ← extracts on build

mvn skillsjars:extract -Ddir=skills/                      ← populates skills/ dir

application.properties
└── quarkus.langchain4j.skills.directories=skills          ← Quarkus picks it up

CDI auto-registration
└── SkillsToolProvider bean available to @RegisterAiService
└── Your agents (Brahma, Vishnu, etc.) can declare toolProvider = SkillsToolProvider.class
```

The key difference from your current ArcClaw `ToolCapability` model is that **skills are dynamic** — they come from `SKILL.md` files at runtime, not from a statically compiled `ToolCapability` enum. You would likely want to gate which roles can access `SkillsToolProvider` just like you gate `SANDBOX_EXEC` to coder only.