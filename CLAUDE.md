# Claude Project Rules

Read [AGENTS.md](AGENTS.md) before making changes; it is the canonical guide.

## Local reminders

- Use `ToolCapability.toolName()`, `ChannelType.*.id()`, and
  `LogUtil.rootMessage(e)` instead of duplicated literals or raw exception messages.
- `ReplyNode` is sealed: use a pattern-matching `switch` on concrete node types.
- PMD limits: 500 lines per file, 80 per method, cognitive complexity 15.
- SQLite diagnostics: `sqlite3 data/amsha-meta.db ".tables"` and
  `sqlite3 data/amsha-fts.db ".tables"`.
- Local OMLX/ONNX validation uses `mise run smoke:local-runtime -- <command>`. The
  mise task must continue to delegate through `scripts/local_runtime_smoke.sh` to
  `scripts/local_runtime_smoke.py`; see the reusable-smoke section in `AGENTS.md`.
- Use `omlx` for provider-only checks, `running` for an existing Amsha process, and
  `native --binary dist/amsha-sqlite` for the managed native lifecycle. Credentials
  belong only in the ignored `.env` or process environment; never print or pass them
  on the command line. This smoke path must use OMLX through `generic-openai`, never
  Ollama, and must not be represented as formal cross-platform release evidence.

## Babysitter

For complex workflows, use `/skill:babysit`. This project uses semi-autonomous,
evolutionary, spec-driven refinement; see `.a5c/project-profile.json` and `.a5c/`.
