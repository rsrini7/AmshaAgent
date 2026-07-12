# Claude Project Rules

Read [AGENTS.md](AGENTS.md) before making changes; it is the canonical guide.

## Local reminders

- Use `ToolCapability.toolName()`, `ChannelType.*.id()`, and
  `LogUtil.rootMessage(e)` instead of duplicated literals or raw exception messages.
- `ReplyNode` is sealed: use a pattern-matching `switch` on concrete node types.
- PMD limits: 500 lines per file, 80 per method, cognitive complexity 15.
- SQLite diagnostics: `sqlite3 data/amsha-meta.db ".tables"` and
  `sqlite3 data/amsha-fts.db ".tables"`.

## Babysitter

For complex workflows, use `/skill:babysit`. This project uses semi-autonomous,
evolutionary, spec-driven refinement; see `.a5c/project-profile.json` and `.a5c/`.
