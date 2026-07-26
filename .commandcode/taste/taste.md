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
See [workflow/taste.md](workflow/taste.md)
# architecture
See [architecture/taste.md](architecture/taste.md)
