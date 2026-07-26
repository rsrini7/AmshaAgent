# workflow
- When diagnosing test failures, first collect all failure reports and identify common root causes across modules before fixing individual tests. Confidence: 0.75
- For Quarkus multi-module projects, set `quarkus.http.test-port=0` in the parent POM's surefire `systemPropertyVariables` so all modules inherit random test ports and avoid port conflicts. Confidence: 0.80
- Keep build output clean — fix spurious IDE/lifecycle-mapping warnings (e.g., m2e antrun plugin goals) when they appear, in addition to the primary issue. Confidence: 0.65
- Skip DB data migration documentation for the project — during active development, delete the data folder instead of running DB migrations. Confidence: 0.80
- Keep DB migration version history clean by moving alter-table changes into the original table migration and combining closely related consecutive migrations (e.g., V7 and V9) rather than accumulating many sequential small patches. Confidence: 0.60
- Store reusable seed/test SQL files under script/data/ for shell script reuse. Confidence: 0.70
- Shell scripts and tests (validators, runners) must be generic and work with both jar and native binary deployments — do not hardcode assumptions about dist paths or binary type. Confidence: 0.65
- When a script needs a data directory path, ask the user to provide it rather than auto-detecting from the running process. Confidence: 0.75
