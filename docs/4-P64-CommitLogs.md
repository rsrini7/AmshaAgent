fix(governance): ensure atomic budget updates and correct exception ordering

- Replace atomic fields with synchronized block to guarantee atomicity of cost accumulation and token counting
- Reorder budget checks: hard limit now triggers before warning to prevent warning on single massive call
- Add concurrent test to verify atomic updates and new test for hard limit enforcement

---

feat(autonomy): add session-level autonomy control and recovery enhancements

- Introduce autonomy level parameter in session creation API with CreateSessionRequest DTO
- Persist autonomy level in checkpoints and propagate through pipeline handlers
- Add recovery configuration for stalled sessions with threshold minutes
- Improve ChannelType parsing to return Optional for better legacy data handling
- Enhance FTS5 search with proper Unicode and special character escaping
- Fix compaction handler to reliably release in-flight lock on success/failure
- Add schema error retry mechanism in reasoning engine with OBSERVE step type
- Strengthen budget governance with pre-check and proper exception propagation
- Update Telegram renderer to use bold/underline formatting for headings
- Add comprehensive test coverage for new autonomy and recovery features

---

docs: update project documentation for Phase 64-A completion

Update TASKS.md, WALKTHROUGH.md, and PLAN.md to reflect the completion of Phase 64-A, which focused on governance and error handling. The documentation now includes details on atomic budget enforcement, FTS5 non-ASCII indexing fixes, REST API error mapping, and other critical bug resolutions.

---

fix(logging): separate error messages from stack traces

Improve log readability by separating error messages from stack traces. Error logs now contain only the message while stack traces are logged separately at DEBUG level. This prevents log aggregation systems from treating stack traces as part of the error message and makes troubleshooting more efficient.

---

style: optimize import statements and fully qualify class names

- Standardize import ordering across multiple test and main files
- Replace fully qualified class names with import statements where possible
- Fix static import usage for better code readability
- Maintain consistent code style without changing functionality

---

feat: add scripts to organize imports and fix fully qualified names

Add two Python scripts to automate Java source code maintenance:
- organize_imports.py: sorts imports into standard categories and groups them with blank lines
- fix_fqns.py: replaces fully qualified names with imports where possible, handling collisions and module-specific import rules
Both scripts process .java files recursively, skipping target and .git directories.

---

refactor(imports): improve import organization and FQN handling using fix_fqns.py and organize_imports.py

- Preserve copyright/license headers and line endings in both scripts
- Skip redundant java.lang.* imports (auto-imported by Java)
- Improve detection of FQNs inside comments and string literals
- Add proper block comment awareness to avoid false positives
- Handle module boundaries to prevent illegal cross-module imports
- Keep Caffeine internal classes fully qualified to avoid collisions

---

docs: add search and test diagnostic cheatsheets

Add two markdown reference files:
- FQN2Import.md: commands for finding and replacing fully-qualified names in Java code
- arcclaw_rg_cheatsheet.md: comprehensive ripgrep patterns and Maven test diagnostics for the ArcClaw project

These documents serve as developer references for common codebase search operations and test troubleshooting workflows.

---

style: reorder imports to follow Java conventions

Apply consistent import ordering across the codebase by moving static imports after non-static imports and grouping jakarta imports before other imports. This improves readability and aligns with standard Java style guidelines.

---

feat: add channel-aware session resolution and observability

- Add mandatory channelId field to ChannelUser for proper session resolution
- Replace legacy MD5 session IDs with persistent SessionRegistry (UUID v4)
- Introduce SessionIdResolver interface with production (SessionRegistry) and fallback (UUID v3) implementations
- Add OpenTelemetry dependency for observability
- Implement atomic audit sequencing with SessionAuditSequencer to prevent TOCTOU races
- Create extensible ChannelJobNotifier interface for multi-channel cron/approval notifications
- Replace hardcoded Telegram observers with generic dispatchers (ChannelCronNotificationDispatcher, ChannelApprovalTimeoutDispatcher)
- Add RagChunkEntity for standalone RAG storage separate from compaction summaries
- Guard DevSecretSeeder with @IfBuildProfile("dev") to prevent production exposure
- Update all tests to use new ChannelUser constructor with channelId parameter

---

docs: update project documentation for phases 64-A, 64-B, and 64-C

Add comprehensive documentation for the recently completed phases covering governance, security, and architectural improvements. The TASKS.md file is updated to mark all steps as completed. PLAN.md includes detailed design decisions and proposed changes for each phase. WALKTHROUGH.md provides implementation summaries and verification results. TROUBLESHOOTING.md adds detailed remediation guides for security vulnerabilities and migration notes.
