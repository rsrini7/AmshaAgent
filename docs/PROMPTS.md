# PROMPTS.md — ArcClaw Reusable Prompt Templates
> Copy the relevant template. Fill in [brackets]. Paste into Perplexity/Gemini/Claude.
> Always prepend TRUNK_CONTEXT.md content, then GOTCHAS.md Section A before any template.
> Templates are ordered by phase workflow sequence.

---

## TEMPLATE 1 — PHASE PLANNING (Gemini — Architect role)
> Use: At the start of every new phase, before any code.
> Goal: Generate implementation trajectories for you to choose from.

```
ROLE: Senior Architect and Developer. ArcClaw ecosystem.

CONTEXT (read fully before answering):
[PASTE TRUNK_CONTEXT.md]

CONSTRAINTS (mandatory, non-negotiable):
[PASTE GOTCHAS.md SECTION A — G-01 to G-17]

COMPLETED PHASES (DO NOT re-suggest or re-implement):
Phases 0 through 46 are complete. See TASKS.md for the full list.

CURRENT PHASE OBJECTIVE:
[Describe what Phase N needs to deliver in 2-3 sentences]

YOUR TASK:
Propose exactly 3 implementation trajectories for this phase.
For each trajectory provide:
  1. Approach name (one line)
  2. Key files to create/modify (list)
  3. Pros (2-3 bullets)
  4. Cons / risks (2-3 bullets)
  5. Alignment check: does this violate any GOTCHA or ADR? (yes/no with reason)
  6. Estimated new files vs modified files (count)

Do NOT write any code. Propose trajectories only.
After proposing, ask: "Which trajectory do you want to lock?"
```

---

## TEMPLATE 2 — TRAJECTORY CHALLENGE (Gemini — Devil's Advocate role)
> Use: After TEMPLATE 1 produces the trajectories, BEFORE locking one.
> Goal: Find failure modes specific to ArcClaw that the Architect missed.
> Paste the chosen trajectory from Template 1 as input here.

```
ROLE: Devil's Advocate — Senior Security and Concurrency Engineer.
Your job is to BREAK the proposed trajectory, not improve it.
Do NOT suggest implementation. Find failure modes ONLY.

CONTEXT:
[PASTE TRUNK_CONTEXT.md]

PROPOSED TRAJECTORY (from Architect):
[PASTE the chosen trajectory from Template 1]

YOUR TASK:
Challenge this trajectory aggressively. For each concern, answer:
  - What specifically can break in ArcClaw's constraints?
  - Which module boundary does it violate?
  - Which GOTCHA does it risk triggering?
  - What concurrency/SQLite WAL edge case is unaddressed?
  - What happens on JVM restart mid-execution?
  - What happens under BACKGROUND autonomy level restrictions?
  - What happens when the Podman circuit breaker is in OPEN state?

Produce a numbered list of concerns only. No code. No fixes.
End with: "Concern count: [N]. Severity distribution: Critical [N] / High [N] / Low [N]."
```

---

## TEMPLATE 3 — TRAJECTORY SYNTHESIS (Claude/Perplexity — Synthesizer role)
> Use: After Template 2 produces concerns.
> Goal: Merge Architect's trajectory + Devil's Advocate concerns into the final locked plan.
> Paste both Template 1 output and Template 2 output as inputs.

```
ROLE: Senior Synthesizer — merge the best of the Architect and Devil's Advocate.

CONTEXT:
[PASTE TRUNK_CONTEXT.md]

ARCHITECT'S TRAJECTORY:
[PASTE Template 1 chosen trajectory]

DEVIL'S ADVOCATE CONCERNS:
[PASTE Template 2 concerns list]

YOUR TASK:
1. For each Devil's Advocate concern, state: Addressed / Partially Addressed / Accepted Risk / Out of Scope
2. Produce the FINAL TRAJECTORY LOCK with:
   - Chosen approach (one sentence)
   - Rejected alternatives (from Template 1 non-chosen trajectories)
   - Hard constraints (which files must NOT be touched)
   - Module impact (core / deployment / app — NEW/MODIFY/NONE)
   - Mitigations for each Critical/High concern from the Devil's Advocate
3. End with: "TRAJECTORY LOCKED. Ready for implementation prompt."

Do NOT write any code yet.
```

---

## TEMPLATE 4 — FINAL IMPLEMENTATION (Antigravity IDE / Claude)
> Use: After TRAJECTORY_LOCK is produced by Template 3 and signed off by you.
> Goal: Generate production-ready code with zero ambiguity.

```
ROLE: Senior Java Developer. ArcClaw ecosystem.

CONTEXT:
[PASTE TRUNK_CONTEXT.md]

MANDATORY RULES — READ BEFORE WRITING ANY CODE:
[PASTE GOTCHAS.md SECTION A — G-01 to G-17]
[PASTE GOTCHAS.md SECTION C — G-22 to G-28]

TRAJECTORY LOCK (approved):
[PASTE final TRAJECTORY_LOCK from Template 3]

IMPLEMENTATION REQUIREMENTS:
[PASTE Section 4 of PHASE_TEMPLATE.md — Component List]

BEFORE WRITING ANY CODE:
1. Acknowledge the TRAJECTORY_LOCK.
2. State: which files will be created (NEW) and which modified (MODIFY).
3. Confirm: no GOTCHA rules are violated.
4. Then write the complete implementation.

OUTPUT FORMAT:
- One file at a time, with full file path as header.
- Full file content only (no partial snippets).
- After all files: list all test cases added/modified with their assertion logic.
- Final line: "Implementation complete. [N] new files, [N] modified files, [N] test cases."
```

---

## TEMPLATE 5 — PHASE CLOSE AND COMPRESS (Claude/Perplexity)
> Use: After all code is generated, tests pass, and git diff is ready.
> Goal: Produce compressed summaries for TRUNK_CONTEXT, WALKTHROUGH, and GOTCHAS updates.
> Paste the git diff and PHASE_TEMPLATE.md Sections 5-7 as input.

```
ROLE: Senior Technical Writer and Architect. ArcClaw ecosystem.

CONTEXT:
[PASTE TRUNK_CONTEXT.md]

PHASE N COMPLETED. Here is what was built:
[PASTE PHASE_TEMPLATE.md Sections 5, 6, 7 — Design Decisions, Verification, Completed Changes]

GIT DIFF (Phase N):
[PASTE git diff --stat and key diff sections]

YOUR TASK — Generate 4 outputs:

OUTPUT 1 — TRUNK_CONTEXT UPDATE (max 200 words):
Compressed phase summary for TRUNK_CONTEXT.md "Recent Phase Summaries" section.
Format: "Phase N — [Name]: [What was built]. Key decisions: [2-3 decisions]. Known debt: [1-2 items]."

OUTPUT 2 — WALKTHROUGH ENTRY (max 400 words):
Full narrative for WALKTHROUGH.md covering: what was built, why the approach was chosen,
key deviations from the original plan, and lessons learned.

OUTPUT 3 — NEW GOTCHAS (if any):
Any new "never do this" rules discovered during this phase.
Format: "G-[XX]: NEVER [rule] — because [why] — [phase N] burned [N] iterations."
If none, write: "No new gotchas."

OUTPUT 4 — ADR CANDIDATE (if any):
If a new architectural decision was made that affects future phases, draft the ADR entry.
Format: "AD-[XX] [Title] [Date] / Status: Accepted / Context: [1 sentence] / Decision: [2-3 sentences]"
If none, write: "No new ADR required."
```

---

## TEMPLATE 6 — TROUBLESHOOT (Claude/Perplexity — when stuck)
> Use: When a phase goes off-trajectory, tests fail unexpectedly, or errors repeat.
> Goal: Diagnose and recover without blowing up the current trajectory.

```
ROLE: Senior Debugging Engineer. ArcClaw ecosystem.

CONTEXT:
[PASTE TRUNK_CONTEXT.md]

MANDATORY READ — GOTCHAS:
[PASTE GOTCHAS.md SECTION A — G-01 to G-17]
[PASTE GOTCHAS.md SECTION D — G-29 to G-32]

CURRENT PHASE: [N]
TRAJECTORY LOCK (what was planned):
[PASTE TRAJECTORY_LOCK]

FAILURE DESCRIPTION:
- Error/symptom: [exact error message or test failure]
- When it occurs: [during build / test / runtime / specific operation]
- What was last changed: [paste relevant git diff or file snippet]
- What was already tried: [list attempts]

YOUR TASK:
1. Identify the root cause. Cross-check against GOTCHAS.md first.
2. State: is this a known ArcClaw failure mode? (yes/no, with reference)
3. Propose the minimal fix that stays on the TRAJECTORY_LOCK.
4. State: which files need to change and exactly how.
5. If the trajectory must be abandoned, say so explicitly and recommend a new TRAJECTORY_LOCK entry.

Do NOT suggest solutions that violate any GOTCHA rule.
```

---

## QUICK REFERENCE — TEMPLATE SELECTION GUIDE

| Situation | Use Template |
|---|---|
| Starting a new phase | T1 (Planning) → T2 (Challenge) → T3 (Synthesis) → T4 (Implementation) → T5 (Close) |
| Phase is complex / high risk | Run T1 + T2 + T3 before T4. Never skip the debate for high-complexity phases. |
| Phase is simple / well-understood | T1 (single trajectory only) → T4 directly |
| Something broke mid-phase | T6 (Troubleshoot) |
| Phase is done, tests pass | T5 (Close and Compress) |
| New team member / AI session needs context | Paste TRUNK_CONTEXT.md + GOTCHAS.md Section A only |

---
*Cross-reference: TRUNK_CONTEXT.md | GOTCHAS.md | PHASE_TEMPLATE.md | DEBATE_PROTOCOL.md*
