# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# logging
- Use `LogUtil.java` from the `common` module for logging exceptions instead of direct logger calls in catch blocks. Confidence: 0.65

# code-style
- Use `%n` instead of `\n` for platform-specific line separators when constructing format strings. Confidence: 0.65

# documentation
- Maintain architecture invariants and cross-referencing docs (agents.md, invariants) to capture all backend implementations when adding new store ports, preventing single-backend gaps. Confidence: 0.72
- After implementing features, proactively check and update all project documentation — README Key Capabilities, agents.md, LLM instruction files (gemini.md, claude.md), architecture diagrams (SVG), and docs/ folder — without waiting to be asked; then condense where possible. Confidence: 0.75
- Before implementing from an ADR, ensure the ADR has been reviewed for blocking gaps (categorized as blocking/should-fix/nice-to-have) and updated to resolve all blocking issues. Confidence: 0.70

# workflow
- Skip DB data migration documentation for the project — during active development, delete the data folder instead of running DB migrations. Confidence: 0.80
- Keep DB migration version history clean by moving alter-table changes into the original table migration and combining closely related consecutive migrations (e.g., V7 and V9) rather than accumulating many sequential small patches. Confidence: 0.60
- Store reusable seed/test SQL files under script/data/ for shell script reuse. Confidence: 0.70
- Shell scripts and tests (validators, runners) must be generic and work with both jar and native binary deployments — do not hardcode assumptions about dist paths or binary type. Confidence: 0.65
- When a script needs a data directory path, ask the user to provide it rather than auto-detecting from the running process. Confidence: 0.75

# architecture
See [architecture/taste.md](architecture/taste.md)
