# PHASE_TEMPLATE.md — ArcClaw New Phase Template
> Copy this entire file, rename it `PHASE_N_<NAME>.md`, fill in all sections.
> Complete sections in ORDER. Do not paste to IDE until TRAJECTORY_LOCK is signed off.

---

# Phase [N] — [Phase Name]

**Status:** [ ] Planning → [ ] Trajectory Locked → [ ] In Progress → [ ] Verified → [ ] Closed
**Complexity Estimate:** [ ] Low (< 3h) / [ ] Medium (3–6h) / [ ] High (6–12h)
**Date Started:**
**Date Closed:**

---

## 1. OBJECTIVE
> One paragraph. What does this phase deliver? What capability does the user/system gain?

[Fill in]

---

## 2. CROSS-REFERENCE
- TASKS.md: Phase [N] tasks → items [id:X] to [id:Y]
- PLAN.md: Phase [N] plan section
- ARCHITECTURE_DECISION.md: ADR-[XX] (if any new decision)
- WALKTHROUGH.md: Phase [N] walkthrough (fill after completion)
- TROUBLESHOOTING.md: Phase [N] section (fill if issues hit)

---

## 3. TRAJECTORY_LOCK
> **FILL THIS BEFORE ANY CODE IS GENERATED.**
> Paste to IDE as the first thing: "Acknowledge this TRAJECTORY_LOCK before writing any code."
> If the AI suggests a different approach, do NOT engage — paste the lock again.

### Chosen Approach
[One sentence describing the implementation approach, e.g., "Implement WebSocket channel as a Camel route mirroring TelegramAgentHandler pattern"]

### Rejected Alternatives
| Approach | Reason Rejected |
|---|---|
| [Alternative 1] | [Why rejected, e.g., not in Quarkus ecosystem] |
| [Alternative 2] | [Why rejected] |

### Hard Constraints
- Zero changes to: [list files/classes that must NOT be modified, e.g., `AgentLoop`, `GovernanceLayer`]
- Must follow: [specific patterns to mirror, e.g., "mirror TelegramAgentHandler structure"]
- Must NOT introduce: [e.g., "no new application-scoped maps without GC cleanup hook in WorkspaceManager"]

### Module Impact
- `arcclaw-core`: [NEW / MODIFY / NONE] — [files]
- `arcclaw-deployment`: [NEW / MODIFY / NONE] — [files]
- `arcclaw-app`: [NEW / MODIFY / NONE] — [files]

---

## 4. IMPLEMENTATION REQUIREMENTS

### Common Requirements (Always include in prompt)
```
Role: Senior Security Architect and Java Developer for ArcClaw ecosystem
Architecture: Java 25 / Quarkus 3.23.0 / LangChain4J 1.0.0-beta4 / SQLite WAL /
              Groq Cloud / Podman sandbox / Apache Camel Telegram / AES-GCM-SIV vault
Read: TRUNK_CONTEXT.md (pasted above)
Read: GOTCHAS.md Section A rules G-01 to G-17 (pasted above)
Completed Phases: 0-46. DO NOT re-suggest or re-implement any of these.
```

### Component List
For each component, use this structure:

---
**Component [N] — [ComponentName]**
- **File:** `[full/path/ClassName.java]`
- **Current Problem:** [what's missing or broken]
- **Required Changes:** [specific changes needed]
- **Test:** `[full/path/ClassNameTest.java]` — [what to add/update]
---

### Testing Requirements
```
Run command: mvn test -pl arcclaw-app -DtestClassNameTest -Dsurefire.failIfNoSpecifiedTests=false
Expected: [N] tests passing
```

---

## 5. DESIGN DECISIONS
> Fill during/after implementation. New decisions become ADR candidates.

| Decision | Rationale | Considered Alternatives |
|---|---|---|
| [Decision made] | [Why] | [What else was considered] |

---

## 6. VERIFICATION PLAN

### Automated Tests
| Test Class | Test Count | What It Verifies |
|---|---|---|
| [ClassName]Test | [N] | [What] |

### Manual Verification Steps (if any)
1. [Step 1]
2. [Step 2]

### Build Health Gate
- [ ] `mvn clean verify -pl arcclaw-app -am` — ALL [N] tests pass
- [ ] No new compiler warnings introduced
- [ ] No `@SuppressWarnings` added without comment

---

## 7. COMPLETED CHANGES
> Fill AFTER implementation. This becomes the WALKTHROUGH entry.

### New Files
| File | Purpose |
|---|---|
| `[path]` | [one-line purpose] |

### Modified Files
| File | What Changed |
|---|---|
| `[path]` | [one-line change] |

### Key Deviation (if any)
> If implementation deviated from TRAJECTORY_LOCK, document here with reason.

[None / Description]

---

## 8. PHASE CLOSE — COMPRESSED SUMMARY
> Ask Claude/Gemini to generate this AFTER phase is complete.
> Paste the compressed version into TRUNK_CONTEXT.md under "Recent Phase Summaries".
> Paste the full version into WALKTHROUGH.md.

### Architecture Impact (10 bullets max)
1.
2.
3.

### Behavior Notes (10 bullets max)
1.
2.
3.

### Known Risks / Tech Debt (10 bullets max)
1.
2.
3.

### New GOTCHAS (if any traps were hit — add to GOTCHAS.md)
- G-[XX]: [Rule]

### ADR Entry Required? [ ] Yes → ADR-[XX] / [ ] No

---

## 9. POST-PHASE MD FILE UPDATES
After code is merged and verified, update these files:
- [ ] `TASKS.md` — tick off Phase [N] tasks
- [ ] `PLAN.md` — update Phase [N] status
- [ ] `WALKTHROUGH.md` — add Section 7 (Completed Changes) content
- [ ] `TROUBLESHOOTING.md` — add any new failure modes from Section 5
- [ ] `ARCHITECTURE_DECISION.md` — add ADR if Section 8 flagged one
- [ ] `TRUNK_CONTEXT.md` — append compressed summary (Section 8) to "Recent Phase Summaries"
- [ ] `GOTCHAS.md` — add new rules from Section 8

---
*Cross-reference: TRUNK_CONTEXT.md | GOTCHAS.md | PROMPTS.md | DEBATE_PROTOCOL.md*
