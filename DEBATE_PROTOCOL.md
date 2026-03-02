# DEBATE_PROTOCOL.md — ArcClaw AI Debate Workflow
> This document defines the asymmetric role assignments and rules for the
> Gemini ↔ Claude/Perplexity debate loop used in Phase Planning.
> Follow this EXACTLY. Symmetric debates converge too early and miss edge cases.

---

## Why Asymmetric Roles Matter
When both models play "senior architect," they converge to the same solution in Round 1.
ArcClaw's hardest bugs (e.g., Phase 10: 52 iterations; Phase 44: SQLITE_BUSY cascades)
came from edge cases that a symmetric debate would have missed.
Asymmetric roles force one model to BREAK the plan while the other BUILDS it.

---

## Role Assignments

| Round | Gemini Role | Claude / Perplexity Role |
|---|---|---|
| **Round 1** | 🔴 **Devil's Advocate** — Find failure modes. Never suggest implementation. | 🔵 **Architect** — Propose the implementation trajectory. No criticism yet. |
| **Round 2** | 🟡 **Verifier** — Confirm the Architect's plan addresses Devil's Advocate concerns. | 🟢 **Refiner** — Update the trajectory incorporating Verifier's confirmed concerns. |
| **Final** | Both agree on trajectory → you write `TRAJECTORY_LOCK` | |

---

## Round 1 Opening Script

### To Gemini (Devil's Advocate):
```
Your role this session is DEVIL'S ADVOCATE — Senior Security and Concurrency Engineer.
Your ONLY job is to find failure modes. Do NOT suggest implementation approaches.

Here is the proposed trajectory for ArcClaw Phase [N]:
[PASTE trajectory or objective]

Challenge it aggressively across these specific ArcClaw failure surfaces:
1. SQLite WAL: read-lock / write-lock conflicts, SQLITE_BUSY under concurrent polling
2. Quarkus CDI Lite: module boundary violations, classloader isolation, Jandex gaps
3. Virtual threads (JEP 491): context propagation loss, ScopedValue propagation via Camel/JAX-RS
4. Podman circuit breaker: behavior when OPEN, fallback to NAMESPACE, fallback to NONE
5. BACKGROUND autonomy: which tools are blocked, fork bomb risk
6. Optimistic locking: @Version conflicts on AgentCheckpoint during concurrent tool execution
7. GovernanceLayer budget: what happens if BudgetExceededException fires mid-operation
8. WorkspaceManager GC: what happens to AWAITING_APPROVAL sessions during a GC sweep
9. Test infrastructure: which GOTCHA rules does this risk triggering (reference by ID)
10. Native image: any reflection, dynamic proxy, or BouncyCastle runtime init risk

Output: Numbered list of concerns ONLY.
End with: "Concern count: [N]. Critical: [N], High: [N], Low: [N]."
```

### To Claude / Perplexity (Architect):
```
Your role this session is ARCHITECT — Senior Java Developer for ArcClaw.
Your ONLY job is to design the implementation trajectory. Do NOT critique yet.

[PASTE TRUNK_CONTEXT.md]
[PASTE GOTCHAS.md Section A]

Phase [N] Objective: [description]

Design the implementation trajectory:
1. Chosen approach (one sentence)
2. Files to create (NEW) and modify (MODIFY) — with full paths
3. Module impact: arcclaw-core / arcclaw-deployment / arcclaw-app
4. Key design decisions (3-5 bullets)
5. Test strategy (which classes, what assertions)

Do NOT critique your own design. Do NOT mention failure modes. Build only.
```

---

## Round 2 Opening Script

### To Gemini (Verifier):
```
You are now VERIFIER. Switch from finding problems to confirming solutions.

ARCHITECT'S TRAJECTORY:
[PASTE Round 1 Architect output]

DEVIL'S ADVOCATE CONCERNS (from your Round 1):
[PASTE Round 1 Gemini concerns list]

For each concern you raised:
1. Does the Architect's trajectory address it? (Yes / Partially / No)
2. If Partially or No: what specific change in the trajectory would fully address it?
3. Are there any NEW concerns the Architect's trajectory revealed that Round 1 missed?

Output: Table with columns: Concern # | Addressed? | Required Change (if any)
End with: "Blocking issues for trajectory lock: [N]"
```

### To Claude / Perplexity (Refiner):
```
You are now REFINER.

ORIGINAL TRAJECTORY:
[PASTE Round 1 Architect output]

VERIFIER'S TABLE:
[PASTE Round 2 Gemini table]

Update the trajectory to address all "Partially" and "No" items.
For each change you make, state which concern it addresses (by number).
If a concern is "Accepted Risk" (not fixable without violating a GOTCHA or ADR), say so explicitly.

Output the FINAL TRAJECTORY with:
- All changes incorporated
- Accepted risks listed
- Hard constraints confirmed
- End with: "TRAJECTORY READY FOR LOCK. Blocking issues resolved: [N/N]."
```

---

## Trajectory Lock Gate
Before writing TRAJECTORY_LOCK in PHASE_TEMPLATE.md, confirm:
- [ ] Gemini Verifier says "Blocking issues: 0"
- [ ] No GOTCHA rule is violated
- [ ] No ADR is overridden without a new ADR draft
- [ ] Module boundary rules respected (no Panache in core, etc.)
- [ ] You (human) have read and approved the final trajectory

Only then paste to Antigravity IDE using **TEMPLATE 4 from PROMPTS.md**.

---

## Debate Shortcuts (When to Skip Rounds)

| Scenario | Action |
|---|---|
| Phase complexity is LOW (< 3h estimate, well-understood pattern) | Skip T2/T3. Use T1 (single trajectory) → T4 directly. |
| Phase mirrors an existing pattern exactly (e.g., new Reflex mirroring `PiiScanReflex`) | Skip T1/T2/T3. Use T4 with "mirror [ExistingClass] pattern exactly" constraint. |
| Phase hit a blocker mid-implementation | Stop. Use T6 (Troubleshoot). Do NOT generate new code until diagnosis is complete. |
| Phase 10-level complexity (brand new CDI/Quarkus mechanism) | Run FULL T1→T2→T3 before T4. Consider a "spike" mini-phase first. |

---

## Debate Quality Signals

### ✅ Good Debate Signs
- Gemini raises concerns you hadn't thought of
- Claude's trajectory changes materially between Round 1 and Round 2
- At least 1 "Accepted Risk" is explicitly documented
- The TRAJECTORY_LOCK is more specific than your original Phase objective

### ⚠️ Warning Signs (run another round or simplify scope)
- Both models agree immediately in Round 1 on a complex phase
- Gemini raises 0 concerns for a HIGH complexity phase
- The Trajectory Lock says "no files modified in arcclaw-core" for a phase that adds new behavior

---
*Cross-reference: TRUNK_CONTEXT.md | GOTCHAS.md | PROMPTS.md | PHASE_TEMPLATE.md*
