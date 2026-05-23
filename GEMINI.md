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

## 4. Security & Planning Best Practices
* **Defense-in-Depth Sanitization**: Never rely solely on client-side HTML sanitization (e.g., DOMPurify). Ensure that LLM-generated output or rich text is sanitized server-side (e.g., using `HtmlSanitizerService` / OWASP HTML Sanitizer) before being broadcast to clients or persisted.
* **Holistic Planning**: When writing implementation plans, review the entire project tree, not just the primary module. Cross-reference architectural reviews to ensure backend channel adapters are updated in lockstep with frontend changes.
