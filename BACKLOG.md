# Architectural Backlog

Items deferred from the current diff — real debt, not required for shipping.

---

## 6. Persistence layer — stop using JPA as SQLite adapter

**Problem:** JPA/Hibernate's ORM model is a poor fit for SQLite's concurrency model. Native query bypasses prove the mismatch — checkpoint/session-heavy paths need a dedicated adapter.

**Direction:**
- Introduce a dedicated SQLite adapter using **jOOQ or JDBI** for checkpoint/session-heavy paths.
- Keep JPA only where aggregate lifecycle actually benefits from ORM.

---

## 7. Prompt system — externalize hardcoded prompts

**Problem:** Prompt text lives in compile-time constants with no versioning or A/B testing capability.

**Direction:**
- Replace large static prompt blocks with a `PromptRegistry`.
- Back by DB/Git/YAML storage.
- Add version, status, rollout percentage, and audit metadata.

---

## 8. `AgentStateMachineRunner` — split monolithic loop into State pattern

**Problem:** Large branching loop in the agent runner is hard to isolate-test and evolve.

**Direction:**
Introduce explicit states:
- `ThinkingState`
- `ToolExecutionState`
- `AwaitingApprovalState`
- `TerminalState`

Each owns transition logic and returns the next state explicitly.
