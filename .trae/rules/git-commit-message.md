---
alwaysApply: true
scene: git_message
---

## Core Format

Every commit message should follow this standardized structure:

```text
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

---

## 1. Type: The Nature of the Change

Use a lowercase keyword to define the exact nature of your commit. Avoid mixing types.

| Type | Use Case | Example |
| :--- | :--- | :--- |
| **feat** | New agent capabilities, tools, or channel features. | `feat(tool): add filesystem browser tool` |
| **fix** | Bug fixes, transaction deadlocks, or logic errors. | `fix(core): resolve SQLITE_BUSY in resume path` |
| **refactor** | Code restructuring (e.g. P95 storage extraction). | `refactor(persistence): extract SQLite port implementation` |
| **perf** | LLM prompt optimization or DB query tuning. | `perf(rag): optimize vector search recall` |
| **ui** | Frontend changes in `arcclaw-ui`. | `ui(chat): add websocket reconnect indicator` |
| **test** | Adding or updating unit/integration tests. | `test(hitl): add race condition test for approvals` |
| **docs** | Updates to READMEs, ARCHITECTURE.md, or Tier docs. | `docs(Tire5): mark circuit breaker as completed` |
| **chore** | Dependencies, Maven plugins, or minor config tweaks. | `chore(deps): bump langchain4j to 0.31.0` |
| **build** | CI/CD, Dockerfiles, or Quarkus native-image config. | `build(native): fix reflection config for MCP DTOs` |
| **infra** | Persistence schema changes or WAL configuration. | `infra(sqlite): add index to audit_event timestamp` |

---

## 2. Scope: The Affected Area

The scope pinpoint the exact module or system affected.

| Category | Description | Examples |
| :--- | :--- | :--- |
| **Modules** | Core Maven modules. | `core`, `app`, `mcp`, `rag`, `ui`, `onboarding` |
| **Persistence** | Storage-related changes. | `persistence/sqlite`, `persistence/api` |
| **Channels** | Communication interfaces. | `channel/telegram`, `channel/web` |
| **Tools** | Specific agent tool implementations. | `tool/playwright`, `tool/memory` |
| **Logic** | Specific sub-systems within core. | `hitl`, `audit`, `governance`, `lifecycle` |

---

## 3. Subject: The Brief Description

The subject line must be concise and clearly state what the commit does.

* Limit the subject to **50 characters or less**.
* Use the **imperative mood** (e.g., "add", "fix", "update").
* Start with a lowercase letter.
* Do not put a period at the end.

| Verb | Use Case | Example Subject |
| :--- | :--- | :--- |
| **add** | Introducing new functionality. | `add concurrency limiter to cron scheduler` |
| **update** | Modifying existing logic/parameters. | `update successThreshold for llm circuit breaker` |
| **fix** | Resolving bugs or errors. | `fix missing agentRole in resume guard` |
| **refactor** | Restructuring code or persistence boundaries. | `refactor markBatchResuming to be db-backed` |
| **remove** | Deleting unused code or resources. | `remove redundant RESUMING check in adapter` |
| **standardize** | Aligning patterns (e.g. ISO-LEX). | `standardize sqlite timestamps to iso-lex` |

---

## 4. Body: Detailed Explanation (Optional)

The body provides context. Separate it from the subject with a blank line. 

* Explain the **what** and the **why** (reference §Invariants where applicable).
* Use a hyphen (`-`) for bulleted lists.

**Example:**
```text
fix(core): resolve race in double-resume guard

- Move ResumeCoordinationGuard from in-memory to DB-backed status CAS
- Add RESUMING status to terminalStatuses exclusion set (§5.18)
- Update SqliteCheckpointStoreAdapter to use native atomic UPDATE
- Fixed intermittent failure in BatchResumeRaceTest
```

---

## 5. Footer: Metadata & Tracking (Optional)

The footer is used for issue tracking and critical warnings.

* **Tracking:** `Related to Tier-5`, `Fixes Phase-84`
* **Invariants:** Reference specific sections like `Ref §5.8`, `See §7.29`
* **Breaking Changes:** Start with `BREAKING CHANGE:` 

---

## 6. Project-Specific Workflow Rules

### Core Mandates
* **Transaction Safety:** Ensure any persistence change follows §5.8 (no REQUIRED->REQUIRES_NEW nesting).
* **Identity Protection:** Context propagation must capture all 6 identifiers (§2.7).
* **Native Safety:** New DTOs/Reflective classes must be registered for reflection (§6.7).
* **Testing:** Never commit a fix without a corresponding reproduction test.
