# SkillsJar Admission and Quarantine

> **Docs**: [README](../README.md) · [Extension Guide](./AMSHA_EXTENSION.md) · [Architecture Invariants](./Architecture_Invariants.md) · [Testing Guide](./TESTING_GUIDE.md)

Amsha treats every third-party SkillsJar as an untrusted candidate. A JAR being
valid Maven content, containing a parseable `SKILL.md`, or working in another
agent host does not make it compatible with Amsha.

SkillsJars pass through build-time and runtime admission gates before they can
receive capabilities or participate in intent routing.

## Why Admission Is Required

Skills from other agent environments may assume tools or execution semantics
that Amsha does not provide. Common incompatible assumptions include:

- sequential `Read`, `Write`, `Edit`, `Glob`, or `Grep` agent tools;
- several successful tool calls within one turn;
- cloud services, API keys, paid subscriptions, or undeclared network access;
- command-line programs that are not installed in the tool runner;
- artifact creation without a script that actually creates and verifies the
  advertised output.

Amsha permits only `FINISH` after a successful tool result. An executable skill
must therefore expose a one-shot entry point that completes its workflow in one
approved invocation.

## Admission Flow

```text
SkillsJar dependency
        |
        v
Build-time verification
        |
        +-- rejected --> dependency must not be bundled
        |
        +-- accepted --> indexed and packaged
                              |
                              v
                     Runtime evaluation
                              |
                              +-- rejected --> quarantined
                              |               no role or capabilities
                              |
                              +-- accepted --> active and routable
```

The build and runtime checks are intentionally independent. Build-time
verification protects committed dependencies, while runtime evaluation protects
JVM fallback loading, extracted resources, and unexpected packaging differences.

## Build-Time Verification

Run the verifier after adding or removing a `com.skillsjars` dependency:

```bash
python3 ./verify_skillsjar_index.py --fix
python3 ./verify_skillsjar_index.py
```

The verifier reads SkillsJar dependencies from `app/pom.xml`, opens each JAR
from the local Maven repository, finds its `SKILL.md` resources, and checks:

- native-image index parity;
- unsupported agent-tool instructions;
- artifact-producing workflows without a one-shot entry point under `scripts/`.

The command exits nonzero when a dependency is rejected. A rejected dependency
must not be merged or released.

The native index is:

```text
app/src/main/resources/META-INF/skills/skills-index.txt
```

An existing index is authoritative even when it contains no skills. This
prevents old files under `~/.amsha/skills/skillsjars` from reactivating after
their Maven dependency is removed.

## Runtime Admission

At startup and skill refresh:

1. `SkillsJarSkillProvider` discovers and parses candidate definitions.
2. `SkillAdmissionEvaluator` evaluates third-party compatibility.
3. `SkillLoader` quarantines rejected definitions.
4. Only accepted definitions are registered as roles and placed in the active
   `SkillRegistry`.
5. Capabilities are granted only after admission succeeds.

SkillsJars with no declared valid capability remain prompt-only. Amsha does not
implicitly grant `host_exec`.

Database and built-in classpath skills are not subject to these third-party
compatibility rules.

## Inspecting Admission Results

Use:

```http
GET /skills/admission
```

The response separates active and quarantined skills:

```json
{
  "active": [
    {
      "id": "example-id",
      "name": "compatible-skill",
      "description": "Runs a one-shot local workflow",
      "source": "skillsjars",
      "triggerPatterns": ["use compatible skill"]
    }
  ],
  "quarantined": [
    {
      "name": "incompatible-art",
      "source": "skillsjars",
      "reasons": [
        "instructions require agent tools (Read/Write/Edit/Glob/Grep) that Amsha does not expose",
        "artifact-producing workflow has no one-shot entry point under scripts/"
      ]
    }
  ]
}
```

Quarantined skills are not available to intent routing and receive no tool
capabilities.

## Compatible Package Shape

An executable artifact-producing skill should use a structure similar to:

```text
META-INF/skills/vendor/package/skill-name/
|-- SKILL.md
|-- scripts/
|   `-- run.sh
|-- templates/
`-- references/
```

The entry point should accept non-interactive input through flags or standard
input and complete all required work:

```text
validate dependencies
  -> generate output
  -> verify output exists and is nonempty
  -> return the verified result
```

Do not design a skill that requires the model to call tools in this sequence:

```text
read template -> generate content -> write file -> inspect file -> open browser
```

Instead, package that workflow behind one script and instruct the model to call
the script directly.

## Review Checklist

Before adding a SkillsJar dependency, confirm:

- `SKILL.md` describes an Amsha-compatible one-shot workflow.
- Every named CLI or interpreter is installed in the intended runtime.
- API keys, authentication, network egress, and paid services are documented.
- Artifact-producing skills include a real `scripts/` entry point.
- The script validates prerequisites and returns a nonzero exit code on failure.
- The script verifies any claimed output before reporting success.
- No instruction assumes unsupported agent tools.
- The dependency passes `verify_skillsjar_index.py`.
- Focused JVM tests cover admission and the representative workflow.
- Native-image resource indexing remains synchronized.

Loading successfully is not an acceptance criterion. A skill is acceptable only
when its representative task produces a verified, useful result under Amsha's
execution constraints.

## Troubleshooting

### Skill appears under `quarantined`

Read the reasons returned by `GET /skills/admission`. Fix the package rather
than broadening capabilities or bypassing the evaluator.

### Removed skill still exists on disk

Extracted files may remain under `~/.amsha/skills/skillsjars`, but an
authoritative index prevents them from becoming active. Delete them separately
only when filesystem cleanup is desired.

### Skill claims an artifact was created, but no file exists

Treat this as a failed workflow. The one-shot script must create and verify the
artifact, and the final response must be based on that successful tool result.
Do not accept a model-generated path as evidence that a file exists.
