# Rule Guidelines for Claude / OpenClaude (CLAUDE.md)

## 1. Project Rules
**Always read and follow [AGENTS.md](./AGENTS.md) before making any code changes.** Key rules:

* **No hardcoded strings**: Use `ToolCapability.ENUM.toolName()` instead of `"sandbox_exec"`, `"github"`, etc.
* **No hardcoded channel types**: Use `ChannelType.CLI.id()` instead of `"cli"`, `ChannelType.TELEGRAM` instead of `"telegram"`.
* **Use LogUtil**: Always `LogUtil.rootMessage(e)` or `LogUtil.trimmedStack(e)`, never raw `e.getMessage()`.
* **Transaction-Free Zone**: No LLM calls, network requests, or tool execution inside `@Transactional`.
* **Null-safe sealed interface access**: If a sealed interface method returns a nullable wrapper (`Boolean`, `Integer`), always use `Boolean.TRUE.equals(x)` or `!= null` guard. Never auto-unbox. See AGENTS.md §1.5.
* **Import alignment**: After editing Java files, run `python3 ./fix_fqns.py`.

## 2. Checkstyle Limits
* **File**: max 500 lines per Java source file
* **Method**: max 80 lines per method
* If a method exceeds 80 lines, extract into private helper methods.

## 3. ReplyNode Pattern
`ReplyNode` is a sealed union of `ContentNode` (non-sealed, for content records) and `InteractionNode` (non-sealed, for interactive controls). Always use `switch` with pattern matching:
```java
return switch (node) {
    case ContentNode.Text t -> t.content();
    case ContentNode.Heading h -> h.text();
    case ContentNode.CodeBlock cb -> cb.code();
    // ... etc
};
```
Never call methods on the base `ReplyNode` — concrete methods live on the subtype.

## 3. Channel Plugin SPI
New channels must implement `ChannelPlugin` with all 6 interfaces:
`ChannelPlugin`, `ChannelSender`, `ReplyRenderer`, `ChannelAllowlist`, `ChannelJobNotifier`, `ChannelDaemon`.

## 4. SQLite Diagnostics
* **Metadata db**: `sqlite3 data/arcclaw-meta.db ".tables"`
* **Search / Vector db**: `sqlite3 data/arcclaw-fts.db ".tables"`

## 5. Full Reference
See [AGENTS.md](./AGENTS.md) for complete developer instructions, architecture invariants, and patterns.
