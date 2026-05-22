# Rule Guidelines for Gemini (GEMINI.md)

## 1. Windows OS Shell Restrictions
In Windows, do not use `dir /s /b` or `grep` (which are prone to failure). 
* Use powershell-friendly commands to find files.
* Use `rg` (ripgrep) which is pre-installed as the replacement for `grep`.
* Do not use `cat -n`; instead use powershell equivalent if running inside a Windows terminal.

## 2. SQLite Database Diagnostics
* **Metadata db**: `sqlite3 data/arcclaw-meta.db ".tables"`
* **Search / Vector db**: `sqlite3 data/arcclaw-fts.db ".tables"`
* Query directly to inspect database structures and states.

## 3. Project Architectural Invariants & Patterns
Refer to [AGENTS.md](./AGENTS.md) for full developer instructions, including:
* The Transaction-Free Zone (no high-latency network/LLM calls inside DB transactions).
* Exception Classification Chain of Responsibility.
* Inbound Message Pipeline abstractions.
