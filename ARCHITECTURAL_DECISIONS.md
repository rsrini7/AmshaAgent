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
