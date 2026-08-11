# Domain documentation

This is a single-context repository.

## Before exploring

- Read root `CONTEXT.md` when it exists.
- Read relevant ADRs matching `Ideas-Pending/ADR-*.md`.
- Preserve the existing ADR naming and location convention; do not introduce
  `docs/adr/`.
- If `CONTEXT.md` is absent, proceed silently. Domain-modeling workflows create
  it lazily when project-specific terminology is resolved.

## Layout

- `CONTEXT.md` — project glossary and ubiquitous language, when present.
- `Ideas-Pending/ADR-*.md` — architectural decisions and proposed decisions.
- `docs/Architecture_Invariants.md` — mandatory cross-cutting architectural
  constraints.

## Consumer rules

Use terminology defined in `CONTEXT.md` in ticket titles, specifications, tests,
and architectural proposals.

When proposed work contradicts an existing ADR or architecture invariant,
identify the conflict explicitly instead of silently overriding it.
