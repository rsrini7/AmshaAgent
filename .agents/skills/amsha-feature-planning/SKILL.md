---
name: amsha-feature-planning
description: Turn a substantial Amsha feature or cross-module enhancement into a repository-grounded plan, specification, architectural decision when needed, and ordered local Markdown issues.
---

# Amsha Feature Planning

Use this workflow to turn a substantial enhancement request into a reviewable delivery plan. The plan is recorded in the specification and linked artifacts. Treat implementation as a separate follow-on unless the request includes it.

## Read the project context

Read [AGENTS.md](../../../AGENTS.md), [domain documentation](../../../docs/agents/domain.md), [issue-tracker conventions](../../../docs/agents/issue-tracker.md), and [triage labels](../../../docs/agents/triage-labels.md). Read root `CONTEXT.md` when present, along with relevant [Architecture Invariants](../../../docs/Architecture_Invariants.md), ADRs, proposals, specifications, issues, source, configuration, and tests.

Use terminology defined in `CONTEXT.md` in issue titles, specifications, tests, and ADRs. Ground design claims in current repository evidence. Distinguish verified facts, decisions, assumptions, and open questions. Surface conflicts with accepted ADRs or invariants before proposing a resolution.

## Define the plan and artifacts

Establish the requested outcome, scope and non-goals, affected modules, dependencies, meaningful alternatives, observable acceptance evidence, and delivery sequence. Keep the plan in the feature specification and linked artifacts. Create a separate proposal under `docs/proposals/` only when the user requests one.

Create an ADR in the existing `docs/adr/<category>/` taxonomy when the work requires an architectural decision. Record its context, options, decision, consequences, relevant invariants, and deferred questions. If no architectural decision is needed, omit the ADR and briefly explain that in the specification.

The feature specification belongs at `.scratch/<feature-slug>/spec.md`. It defines the goal, scope, requirements, acceptance criteria, cross-cutting constraints, verification gates, and ordered issue map.

## Create or update local issues

Keep issues under `.scratch/<feature-slug>/issues/`; never publish them to an external tracker. For a new tracker, use `python3 ./scripts/scaffold_feature_tracker.py` as described in the [issue-tracker guide](../../../docs/agents/issue-tracker.md). Inspect generated files and replace template content. Update existing trackers in place.

Follow the issue-tracker conventions:

- Use one issue per file named `<NN>-<slug>.md`, numbered from `01` in dependency order.
- Record dependencies in `Blocked by:` and use `## Comments` for discussion.
- Set each issue's `Status:` to one of the canonical local values in the [triage guide](../../../docs/agents/triage-labels.md): `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `resolved`, or `wontfix`. Choose the value that reflects the issue's current state; use `needs-triage` while a new issue awaits triage and `ready-for-agent` only when it is actionable. Replace the scaffolder's `Pending` issue status.
- Keep the specification's own status accurate for its lifecycle; it is not an issue triage status.
- Give each issue a clear outcome, acceptance criteria, and proportionate verification. Avoid empty phases and duplicate tickets.

## Review the package

Before finishing:

- Confirm the plan, ADR, specification, and issues agree on scope, decisions, sequencing, status, and open questions.
- Follow every local link in the package and confirm its target exists. `python3 ./scripts/verify_docs.py` skips `.scratch/`, so verify links in the specification and issues separately; a verifier pass does not cover those files.
- Confirm issue numbering and `Blocked by:` edges match the delivery order.
- Check acceptance criteria and verification gates against current `AGENTS.md` instructions and relevant invariants.
- Run `python3 ./scripts/verify_docs.py` after Markdown edits and report any verifier limitation accurately.

Return the artifact paths, a brief summary of the decisions and delivery sequence, and any unresolved questions or assumptions.
