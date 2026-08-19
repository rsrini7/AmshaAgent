# Issue tracker: Local Markdown

Issues and specifications for this repository live as Markdown files under
`.scratch/`. Do not publish work items to GitHub, GitLab, Linear, or another
external tracker.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`.
- A feature specification may live at `.scratch/<feature-slug>/spec.md`.
- Write one ticket per file under `.scratch/<feature-slug>/issues/`.
- Number tickets in dependency order as `<NN>-<slug>.md`, beginning with `01`.
- Record blocking edges in each ticket's `Blocked by:` field.
- Record triage state in each ticket's `Status:` field.
- Append discussion under an `## Comments` heading.
- Never replace individual ticket files with one combined ticket document.

## Skill operations

When a skill says to publish an issue, create the appropriate local Markdown
file. When a skill says to fetch or update an issue, read or edit the referenced
local file. Never call an external issue-tracker API or CLI.

## Scaffolding Automation

Use [`scripts/scaffold_feature_tracker.py`](../../scripts/scaffold_feature_tracker.py)
to generate or synchronize ADRs, feature specifications (`spec.md`), and ordered
issue files (`issues/01-*.md` through `07-*.md`).

```bash
# Generate / synchronize feature trackers and ADRs
./scripts/scaffold_feature_tracker.py
```

### When to use it
- When starting a new major modularization or cross-cutting feature to scaffold the ADR and issue tracker.
- When updating or synchronizing the `.scratch/<feature-slug>/` issues and `spec.md` with architectural review findings.
- Before verifying documentation links with `python3 ./scripts/verify_docs.py`.
