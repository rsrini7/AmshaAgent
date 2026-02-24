Check and append / update all / required points for future reference that should not spend time on it. Based on the md files can proceed by any LLM. - **Do Not Update me**

check git logs for past ~3 and its modified files before doing update. Check its commit message and list of files changed as well.

## Documentation Sync Status (2026-02-23)
- [x] **PLAN.md**: Implementation Plan tracker.
- [x] **TASKS.md**: Implementation Task tracker.
- [x] **WALKTHROUGH.md**: Implementation Walkthrough tracker.
- [x] **DEVIATIONS.md**: Implementation Deviations tracker comparing with ./REQUIREMENTS.md.
- [x] **TROUBLESHOOTING.md**: Implementation Troubleshooting tracker (including Podman Sandbox Strategy).
- [x] **REQUIREMENTS.md**: Implementation Requirements tracker.
- [x] **WSL2_SETUP_GUIDE.md**: New unified setup guide for JDK 25 and Podman.

## Manual Verification Required Before Commit - This is for Human only and AI should not call any tools
1. **Approval Flow**: Send a prompt to Telegram requiring network access (e.g., "Check repo arcclaw"). Verify it pauses and resumes correctly via `/approve`.
2. **Session Reset**: After a task completes, send a new goal. Verify it wipes the old state and starts fresh.
3. **Native Build**: Run `mvn clean package -pl arcclaw-app -am -Pnative` once to ensure no regressions in AOT compilation.
4. **Sandbox Enforcement**: Send a Telegram prompt requiring GitHub access 
   (e.g., "Check repo rsrini7/ArcClaw"). Verify in server logs that 
   `ToolRegistry` (not `GithubTool`) logs the `executeSandboxed` call. 
   GithubTool should never log "Executing 'gh repo view'" directly.
5. **Strict Binding**: Send "Write a Java singleton class" to Telegram.
   Verify Brahma writes code immediately — no /approve prompt appears.
6. **Shiva Isolation**: Send "NullPointerException at ToolRegistry.java:145".
   Verify Shiva analyses it without fetching any GitHub repo.
7. **Reset Command**: Send `/reset`. Verify 🧹 confirmation. Then send a 
   follow-up question. Verify prior session context is NOT referenced.
8. **Coordinator Tool**: Send "fetch rsrini7/mermaid2gif repo with gh".
   Verify coordinator still triggers `/approve` correctly.

> [!TIP]
> **Git Tip**: I have synced the docs. You can now safely `git add .` and commit. I will not override these files blindly in future sessions.
