# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# logging
- Use `LogUtil.java` from the `common` module for logging exceptions instead of direct logger calls in catch blocks. Confidence: 0.65

# documentation
- Maintain architecture invariants and cross-referencing docs (agents.md, invariants) to capture all backend implementations when adding new store ports, preventing single-backend gaps. Confidence: 0.70
- After implementing features, proactively check and update all project documentation — README Key Capabilities, agents.md, LLM instruction files (gemini.md, claude.md), architecture diagrams (SVG), and docs/ folder — without waiting to be asked; then condense where possible. Confidence: 0.75
- Before implementing from an ADR, ensure the ADR has been reviewed for blocking gaps (categorized as blocking/should-fix/nice-to-have) and updated to resolve all blocking issues. Confidence: 0.70

# workflow
- Skip DB data migration documentation for the project — during active development, delete the data folder instead of running DB migrations. Confidence: 0.75
- Store reusable seed/test SQL files under script/data/ for shell script reuse. Confidence: 0.70
- Shell scripts and tests (validators, runners) must be generic and work with both jar and native binary deployments — do not hardcode assumptions about dist paths or binary type. Confidence: 0.65
- When a script needs a data directory path, ask the user to provide it rather than auto-detecting from the running process. Confidence: 0.75

# architecture
- When adding a new BuiltInChannelCommand sealed permit, update all three locations: the permits clause, ChannelCommand.parse() switch, and CommandDispatchHandler.handle switch — and bump the count in ChannelContractTest. Confidence: 0.70
- Never hardcode logic for specific skills — all skill-related code must be generic and extensible, not coupled to any single skill's behavior. Confidence: 0.80
- All user-facing interfaces (CLI, REST, Telegram, WebSocket) must route through a single transport-neutral TurnDispatchService — no interface may bypass it with bespoke workflow logic. Confidence: 0.85
- User-initiated adapters must not directly inject AgentLoop, ReasoningEngine, ReflexRegistry, or SkillAwareIntentDetector — they must call the TurnDispatchService dispatcher. Confidence: 0.80
