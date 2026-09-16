# amsha-memory

Tiered memory services (core / recall / archival), prompt budgeting, and the directive SPIs — extracted from
`amsha-core` in S8 (Issue 08; local tracker: `.scratch/extract-core-subsystems/issues/08-create-amsha-memory.md`).

This is the **one strict zero-core impl module**: it must never import `com.amsha.core` (enforced by ArchUnit
rule 20, `memoryMustNotImportCore`). The dependency direction is core → memory.

## Owns

- `com.amsha.memory.tiered` — `CoreMemoryService`, `RecallMemoryService`, `ArchivalMemoryService`,
  `MemoryWriteGovernance`, `PromptBudgetManager`, `CoreMemoryPromptRenderer`, `RecallMessageMapper`,
  `TieredMemoryProducer`/`TieredMemoryBackend`/`TieredBackendLiteral`, directive SPIs (`DirectiveProvider`,
  `DirectiveFeedbackPort`) and NoOp fallbacks.
- `com.amsha.memory.config` — `TieredMemoryConfig` (`amsha.memory.tiered.*`) and
  `MemoryPromptBudgetConfig` (`amsha.memory.prompt-budget.*`).

Store ports and DTOs stay in `amsha-persistence-api` (`TieredBackend` included); backend implementations live in
the persistence store modules.

## Configuration properties owned by this module

| Property | Default | Env override | Purpose |
| --- | --- | --- | --- |
| `amsha.memory.governance.core-writes-per-minute` | `5` | `AMSHA_MEMORY_GOVERNANCE_CORE_WRITES_PER_MINUTE` | Token-bucket limit for agent-initiated core-memory writes |
| `amsha.memory.governance.archival-writes-per-hour` | `10` | `AMSHA_MEMORY_GOVERNANCE_ARCHIVAL_WRITES_PER_HOUR` | Hourly cap for archival inserts |
| `amsha.memory.prompt-budget.max-core-memory-tokens` | `800` | `AMSHA_MEMORY_PROMPT_BUDGET_CORE_TOKENS` | Core memory block token budget |
| `amsha.memory.prompt-budget.max-compaction-summary-tokens` | `1200` | `AMSHA_MEMORY_PROMPT_BUDGET_COMPACTION_TOKENS` | Compaction-summary block token budget |
| `amsha.memory.prompt-budget.max-prefetched-archival-results` | `3` | `AMSHA_MEMORY_PROMPT_BUDGET_ARCHIVAL_RESULTS` | Max prefetched archival results |
| `amsha.memory.prompt-budget.max-directives` | `5` | `AMSHA_MEMORY_PROMPT_BUDGET_DIRECTIVES` | Max behavioral directives injected per prompt |
| `amsha.memory.prompt-budget.total-non-conversation-tokens` | `2500` | `AMSHA_MEMORY_PROMPT_BUDGET_TOTAL_TOKENS` | Total non-conversation token ceiling |

(`@WithDefault` values on `MemoryPromptBudgetConfig`; runtime values are wired in
`app/src/main/resources/application.yaml` under `amsha.memory.*` and mirrored in `.env.example`.)
