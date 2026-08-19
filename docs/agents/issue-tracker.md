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
