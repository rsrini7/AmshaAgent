# PROMPT — PHASE 65-A: ...... (Tier A)

Role: Senior Java Architect, Bug Fixer & Senior Java Developer  
Task: Phase 65-A — Fix X confirmed correctness defects in live production code.  
All bugs below exist today. No architectural changes. Surgical fixes only.

---

## Architecture Context and Other References
Refer @Architecture_Nuances.md
Refer @arcclaw_rg_cheatsheet.md

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 777 tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 (no preview features).
6. Test commands always use `clean` to prevent stale class false-negatives.

---

### Component A1 — ...

so on..


