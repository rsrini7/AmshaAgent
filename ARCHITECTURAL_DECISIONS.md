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
* **Future Action:** Re-evaluate only if a modern, native-supported Quarkus Discord extension emerges or if Discord-specific features (Slash Commands/Modals) become a core product requirement.
