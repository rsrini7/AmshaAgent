# architecture
- When adding a new BuiltInChannelCommand sealed permit, update all three locations: the permits clause, ChannelCommand.parse() switch, and CommandDispatchHandler.handle switch — and bump the count in ChannelContractTest. Confidence: 0.70
- Never hardcode logic for specific skills — all skill-related code must be generic and extensible, not coupled to any single skill's behavior. Confidence: 0.80
- All user-facing interfaces (CLI, REST, Telegram, WebSocket) must route through a single transport-neutral TurnDispatchService — no interface may bypass it with bespoke workflow logic. Confidence: 0.85
- User-initiated adapters must not directly inject AgentLoop, ReasoningEngine, ReflexRegistry, or SkillAwareIntentDetector — they must call the TurnDispatchService dispatcher. Confidence: 0.80
- Use dedicated reflection configuration classes instead of @RegisterForReflection annotation for native image registration in each module. Confidence: 0.70
- When adding new store adapter ports, register all required DTOs, DAOs, and store adapters in the reflection config for every backend (SQLite, Postgres, FS) per agents.md invariants. Confidence: 0.72
