Check and append / update all / required points for future reference that should not spend time on it. Based on the md files can proceed by any LLM.

## Documentation Sync Status (2026-02-22)
- [x] **PLAN.md**: Updated with Sprint 2B (HITL) and 2C (Dynamic Routing).
- [x] **TASKS.md**: Appended Phase 18 and 19. All current features marked [x].
- [x] **WALKTHROUGH.md**: Added walkthroughs for approval flow and hardening fixes.
- [x] **DEVIATIONS.md**: Documented pivots for resume hooks and deterministic IDs.
- [x] **TROUBLESHOOTING.md**: Added resolutions for "No pending approval" and "Phantom Sessions".
- [x] **REQUIREMENTS.md**: v1.1 Baselined, v1.2 Drafted.

## Manual Verification Required Before Commit
1. **Approval Flow**: Send a prompt to Telegram requiring network access (e.g., "Check repo arcclaw"). Verify it pauses and resumes correctly via `/approve`.
2. **Session Reset**: After a task completes, send a new goal. Verify it wipes the old state and starts fresh.
3. **Native Build**: Run `mvn clean package -pl arcclaw-app -am -Pnative` once to ensure no regressions in AOT compilation.

> [!TIP]
> **Git Tip**: I have synced the docs. You can now safely `git add .` and commit. I will not override these files blindly in future sessions.
