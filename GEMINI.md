# Gemini Project Rules

Read [AGENTS.md](AGENTS.md) for the canonical project rules and required checks.

## Windows shell

- Use `rg` for file and text search; avoid `dir /s /b`, `grep`, and `cat -n`.
- Prefer PowerShell-compatible commands when working in a Windows terminal.

## Diagnostics

- Metadata database: `sqlite3 data/amsha-meta.db ".tables"`
- Search/vector database: `sqlite3 data/amsha-fts.db ".tables"`

## Extra safety

- Sanitize LLM-generated rich text on the server as well as in the UI.
- For cross-cutting changes, inspect all affected modules, persistence backends,
  channels, and UI adapters before implementation.
