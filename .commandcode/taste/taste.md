# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# logging
- Use `LogUtil.java` from the `common` module for logging exceptions instead of direct logger calls in catch blocks. Confidence: 0.65

# documentation
- Maintain architecture invariants and cross-referencing docs (agents.md, invariants) to capture all backend implementations when adding new store ports, preventing single-backend gaps. Confidence: 0.70

# workflow
- Skip DB data migration documentation for the project — during active development, delete the data folder instead of running DB migrations. Confidence: 0.75

# architecture
- Never hardcode logic for specific skills — all skill-related code must be generic and extensible, not coupled to any single skill's behavior. Confidence: 0.80
- All user-facing interfaces (CLI, REST, Telegram, WebSocket) must route through a single transport-neutral TurnDispatchService — no interface may bypass it with bespoke workflow logic. Confidence: 0.85
- User-initiated adapters must not directly inject AgentLoop, ReasoningEngine, ReflexRegistry, or SkillAwareIntentDetector — they must call the TurnDispatchService dispatcher. Confidence: 0.80
