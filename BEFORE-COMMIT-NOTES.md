Check and append / update all / required points for future reference that should not spend time on it. Based on the md files can proceed by any LLM. - **Do Not Update me**

## Documentation Sync Status (2026-02-22)
- [x] **PLAN.md**: Implementation Plan tracker.
- [x] **TASKS.md**: Implementation Task tracker.
- [x] **WALKTHROUGH.md**: Implementation Walkthrough tracker.
- [x] **DEVIATIONS.md**: Implementation Deviations tracker comparing with ./REQUIREMENTS.md.
- [x] **TROUBLESHOOTING.md**: Implementation Troubleshooting tracker.
- [x] **REQUIREMENTS.md**: Implementation Requirements tracker.

## Manual Verification Required Before Commit - This is for Human only and AI should not call any tools
1. **Approval Flow**: Send a prompt to Telegram requiring network access (e.g., "Check repo arcclaw"). Verify it pauses and resumes correctly via `/approve`.
2. **Session Reset**: After a task completes, send a new goal. Verify it wipes the old state and starts fresh.
3. **Native Build**: Run `mvn clean package -pl arcclaw-app -am -Pnative` once to ensure no regressions in AOT compilation.

> [!TIP]
> **Git Tip**: I have synced the docs. You can now safely `git add .` and commit. I will not override these files blindly in future sessions.
