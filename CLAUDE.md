# CLAUDE.md — Quick Rules for Claude

**Always read [AGENTS.md](./AGENTS.md) before making code changes.** Key rules:

* **No hardcoded strings**: `ToolCapability.ENUM.toolName()`, not `"sandbox_exec"`.
* **No hardcoded channel types**: `ChannelType.CLI.id()`, not `"cli"`.
* **Use LogUtil**: `LogUtil.rootMessage(e)`, never raw `e.getMessage()`.
* **Transaction-Free Zone**: No LLM/network/tool calls inside `@Transactional`.
* **Null-safe sealed interfaces**: `Boolean.TRUE.equals(x)`, never auto-unbox. §1.5
* **JDK 25 GA only**: No preview features (structured concurrency, stream gatherers, module imports, value classes). §1.6
* **Continuity vs follow-up**: Only `isExplicitContinuity` restores old goal. §1.7
* **Governance bypass**: Trusted roles skip non-sensitive ops; `NETWORK_OUTBOUND` + `EXECUTE_SHELL` always need HITL. §2.9
* **Garble-strip Telegram**: `stripGarbledLines()` before sending. §3.2
* **Zombie recovery**: Auto-deny ALL stale approvals across all channels. §3.3
* **Saraswati disabled**: `requiresApproval=false` — no delegation to researcher. §3.4
* **Import alignment**: `python3 ./fix_fqns.py && ./mvnw spotless:apply -q`
* **SonarQube**: `mise run sonar:issues` before committing. Fix BLOCKERs.

## ReplyNode Pattern
`ReplyNode` is a sealed union of `ContentNode` and `InteractionNode`. Always use `switch` with pattern matching:
```java
return switch (node) {
    case ContentNode.Text t -> t.content();
    case ContentNode.Heading h -> h.text();
    case ContentNode.CodeBlock cb -> cb.code();
};
```
Never call methods on the base `ReplyNode` — concrete methods live on the subtype.

## PMD Gates
Max: 500 lines/file · 80 lines/method · cognitive complexity ≤ 15.

## SQLite Diagnostics
`sqlite3 data/arcclaw-meta.db ".tables"` · `sqlite3 data/arcclaw-fts.db ".tables"`

## See Also
[AGENTS.md](./AGENTS.md) — full developer guide with invariants, patterns, and checklists.
