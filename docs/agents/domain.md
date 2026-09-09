# Domain documentation

This is a single-context repository.

## Before exploring

- Read root `CONTEXT.md` when it exists.
- Read relevant ADRs matching root or topic-local
  `Ideas-Pending/**/ADR-*.md` paths.
- Preserve the existing ADR naming and location convention; do not introduce
  `docs/adr/`.
- If `CONTEXT.md` is absent, proceed silently. Domain-modeling workflows create
  it lazily when project-specific terminology is resolved.

## Layout

## Working-memory trust boundary

`.workbuddy-ai/memory/` is private, ignored working memory. It may contain stale conclusions, partial command output, or incident notes and is not an architectural authority. Agents must verify every actionable claim against the current source, staged diff, ADR, tests, and runtime state before using it.

Working memory and review reports must contain redacted references only. Never write API keys, access tokens, passwords, authorization headers, raw provider responses, prompts, or secret-bearing malformed input into memory, reports, or committed documentation. If historical notes contain credential-like material, redact or rotate it before sharing or preserving the notes.

- `CONTEXT.md` — project glossary and ubiquitous language, when present.
- `Ideas-Pending/**/ADR-*.md` — architectural decisions and proposed decisions;
  topic folders may co-locate their ADR with supporting evidence.
- `docs/Architecture_Invariants.md` — mandatory cross-cutting architectural
  constraints.

## Consumer rules

Use terminology defined in `CONTEXT.md` in ticket titles, specifications, tests,
and architectural proposals.

When proposed work contradicts an existing ADR or architecture invariant,
identify the conflict explicitly instead of silently overriding it.
