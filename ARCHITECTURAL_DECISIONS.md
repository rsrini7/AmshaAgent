# Architectural Decisions Log

This document records the key architectural decisions made during the development of ArcClaw.

## Channel Adapters

### AD-014: De-prioritization of Discord Channel Adapter (Feb 2026)
* **Status:** Rejected / Deferred.
* **Context:** Attempted to expand communication channels beyond Telegram to include Discord.
* **Findings:**
    * `camel-quarkus-discord`: Does not currently exist as a first-class, native-supported Quarkus extension in the 3.x ecosystem.
    * `quarkus-discord4j` (Quarkiverse): The community extension is effectively abandonware (v0.0.1, no commits since 2024), posing significant security and compatibility risks with modern Discord Gateway Intents.
    * **Native Discord4J**: While possible, direct integration introduces significant boilerplate and reactive-to-imperative threading complexity that is not currently justified by the project's roadmap.
* **Decision:** Maintain Telegram as the sole production-ready channel.

## Data Integrity & Performance

### AD-015: Optimistic Locking & Caffeine Integration (Feb 2026)
* **Status:** Accepted.
* **Context:** High-concurrency agent sessions require stronger data integrity and memory-efficient rate limiting.
* **Decision:**
    * **Optimistic Locking**: Implemented `@Version` on `AgentCheckpoint` and `EncryptedSecret` to prevent lost updates during parallel tool execution or manual manual HITL approvals.
    * **Caffeine LRE**: Replaced `ConcurrentHashMap` with Caffeine `Cache` in `ApiKeyRegistry` to enforce 2-minute idle TTL and 10,000 entry cap, mitigating potential heap exhaustion from large-scale API key usage.
    * **Bulk GC Sweep**: Optimized `WorkspaceManager` to use a single JPQL bulk query for `AWAITING_APPROVAL` sessions, reducing database round-trips during the scheduled sweep.
25: 
26: ## Proactive Autonomy & Cron
27: 
28: ### AD-016: SQLite WAL Concurrency & Split Transactions (March 2026)
29: * **Status:** Accepted.
30: * **Context:** The `agentcron` polling system requires high-concurrency constant reads and occasional atomic writes (claiming). SQLite WAL mode is efficient but brittle to long-held read locks blocking write-lock upgrades.
31: * **Decision:** Implemented **"Split Transactions"** in `AgentCronRepository`. Reads (`findDueCrons`) use `TransactionPhase.NOT_SUPPORTED` to avoid holding locks in the Agroal pool. Writes (`claimForFire`, `markFired`, etc.) use `REQUIRED` to acquire an `IMMEDIATE` lock ONLY during the critical update window, ensuring WAL efficiency and preventing `SQLITE_BUSY` errors during fire cycles.
32: 
33: ### AD-017: BACKGROUND Autonomy & Fork Bomb Prevention (March 2026)
34: * **Status:** Accepted.
35: * **Context:** Background cron jobs need read access (memory/APIs) but must be restricted from recursive behavior or mutations.
36: * **Decision:** 
37:     * **BACKGROUND Level**: Introduced a level between `READONLY` and `SUPERVISED` that explicitly allows read-only tool operations but blocks `SCHEDULE_AUTONOMOUS`.
38:     * **Physical Tool Filtering**: To prevent "Fork Bombs" where an agent schedules an infinite loop of cron jobs, the `LangChainReasoningEngine` detects sessions starting with `cron-` and **physically removes** the `scheduletask` and `manageschedule` tools from the model's sight, ensuring a background task can never schedule another task.
