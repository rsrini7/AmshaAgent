# Claude Project Rules

Read [AGENTS.md](AGENTS.md) before making changes; it is the canonical guide.

## Local reminders

- Never stage or unstage files (`git add`, `git reset`, `git restore --staged`, `git commit`). Leave all git staging and commit decisions exclusively to the user for review.
- Use `ToolCapability.toolName()`, `ChannelType.*.id()`, and
  `LogUtil.rootMessage(e)` instead of duplicated literals or raw exception messages.
- `ReplyNode` is sealed: use a pattern-matching `switch` on concrete node types.
- Java quality gate: run `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`,
  then `./mvnw -pl <affected-module> -am pmd:check -DskipTests`; PMD is bound to
  `verify`, not `package`.
- PMD limits from `app/src/main/resources/amsha-rules.xml`: 500 lines per class,
  80 NCSS statements per method, and cognitive complexity 50.
- Before handoff, run `mise run sonar:issues` after the local Maven gates.
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
