# Domain documentation

This is a single-context repository.

## Before exploring

- Read root `CONTEXT.md` when it exists.
- Read relevant ADRs matching root or topic-local
  `docs/adr/**/ADR-*.md` paths.
- If `CONTEXT.md` is absent, proceed silently. Domain-modeling workflows create
  it lazily when project-specific terminology is resolved.

## Layout

- `CONTEXT.md` — project glossary and ubiquitous language, when present.
- `docs/README.md` — central documentation index with status and navigation.
- `docs/adr/**/ADR-*.md` — architectural decisions (accepted, implemented, or proposed);
  subdirectories group decisions by subsystem (`architecture/`, `providers/`, `features/`, `tools/`, `domain/`).
- `docs/research/` — evaluated options and negative knowledge (historical evaluations that must not be repeated).
- `docs/proposals/` — active implementation proposals and RFCs.
- `docs/roadmap/` — backlog and future ideas.
- `docs/Architecture_Invariants.md` — mandatory cross-cutting architectural
  constraints.

## Working-memory trust boundary

`.workbuddy-ai/memory/` is private, ignored working memory. It may contain stale conclusions, partial command output, or incident notes and is not an architectural authority. Agents must verify every actionable claim against the current source, staged diff, ADR, tests, and runtime state before using it.

Working memory and review reports must contain redacted references only. Never write API keys, access tokens, passwords, authorization headers, raw provider responses, prompts, or secret-bearing malformed input into memory, reports, or committed documentation. If historical notes contain credential-like material, redact or rotate it before sharing or preserving the notes.

## Consumer rules

Use terminology defined in `CONTEXT.md` in ticket titles, specifications, tests,
and architectural proposals.

When proposed work contradicts an existing ADR or architecture invariant,
identify the conflict explicitly instead of silently overriding it.
