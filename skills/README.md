# amsha-skills

Skill loading machinery: providers (classpath / database / SkillsJar), the loader, YAML frontmatter
parser, hot-reload file watcher, admission evaluation, and host-exec skill grants. Extracted from
`amsha-core` in S9 (Issue 09; local tracker: `.scratch/extract-core-subsystems/issues/09-create-amsha-skills.md`).

## Dependency direction (the cycle gate)

`amsha-skills` → `amsha-core`, **one direction only**. Core keeps the consumption contracts
(`SkillRegistry`, `CompiledSkillEntry`, `SkillDefinition`, `SkillFrontmatter`, `SkillPromptParams`,
`SkillAdaptationManager`, `SkillReflex`) and must never declare a dependency on this module — enforced by
ArchUnit rule 18 (`coreShouldNotImportSkillsImpl`, now live) plus the Issue 09 cycle gate
(`grep -c amsha-skills core/pom.xml` = 0). Rule 21 additionally forbids `com.amsha.skills..` from reaching
`com.amsha.core.reflex..`.

## Owns

`com.amsha.skills`: `SkillLoader`, `SkillDefinitionParser`, `SkillProvider` (+ classpath/database/jar
implementations), `SkillFileWatcher`, `SkillAdmissionEvaluator`, `HostExecSkillGrant`.

The loader observes `StartupEvent` and the watcher is `@Startup` — skills load at **bootstrap**, not first
use (startup log shows `Initializing AgentSkills` + `SKILL_LOADED` audit before request handling).

## Integration tests live in app

`SkillLoaderTest` (CDI full-graph boot: loader × registry × role registry × store) lives in
`app/src/test/java/com/amsha/app/skill/` — it needs both core and skills on the classpath, which only app has
(core must not depend on skills; skills' own suite is pure-unit).

## Configuration properties owned by this module

| Property | Default | Purpose |
| --- | --- | --- |
| `amsha.skills.default-max-iterations` | `15` | Default iteration cap injected into skill prompt params |
| `amsha.skills.hot-reload` | — | Enables the skills directory file watcher (app yaml wires it) |
| `amsha.skills.skillsjars.require-approval-for-sensitive` | `true` | SkillsJar sensitive operations require HITL approval |
| `amsha.tools.host-exec.skills` | `jdb-debugger` | Skill roles granted host_exec via `HostExecSkillGrant` |
| `amsha.workspace.root-dir` | `./data/workspaces` | Workspace root watched by `SkillFileWatcher` |

Runtime values are wired in `app/src/main/resources/application.yaml` under `amsha.skills.*` /
`amsha.tools.host-exec.skills` and mirrored in `.env.example`.

## SkillsJar contract

Dynamic SkillsJars are prompt/tool wrappers, not executable engines. Keep
`META-INF/skills/skills-index.txt` in sync with `app/pom.xml`:
`python3 ./scripts/verify_skillsjar_index.py --fix`.
