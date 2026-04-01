## Phase P84A — Onboarding Hardening: Tests, Vault Completeness, Session Persistence & Build Registration

### Context
You are a Senior Java Architect working on the **ArcClaw** project (branch: `P83A`, base for this work). P83A delivered the `arcclaw-onboarding` module with a 5-step wizard, `LlmValidationGateway` SPI in `arcclaw-core`, and `LlmValidationService` in `arcclaw-app`. This phase hardens that foundation. **Do NOT touch Playwright or any frontend code.**

***

### Deliverable 1 — Confirm `arcclaw-onboarding` is wired into the root `pom.xml` `<modules>` section

**File:** `pom.xml` (root)

Check if `<module>arcclaw-onboarding</module>` is already present in the root multi-module `<modules>` block. If it is missing, add it. The correct position is after `arcclaw-mcp` and before `arcclaw-app` so the build order resolves dependencies correctly:

```xml
<modules>
    ...
    <module>arcclaw-core</module>
    <module>arcclaw-mcp</module>
    <module>arcclaw-onboarding</module>   <!-- add if missing -->
    <module>arcclaw-app</module>
    ...
</modules>
```

***

### Deliverable 2 — Fix API key vault storage in `OnboardingResource.step3()`

**File:** `arcclaw-onboarding/src/main/java/com/arcclaw/onboarding/OnboardingResource.java`

Currently `step3` only validates the API key via `LlmValidationGateway.validate()` but does **not** persist it to the vault for non-`claude-code` providers. The `LlmValidationService` stores keys only internally for `claude-code`. Add explicit vault storage after a successful validation:

```java
// After: boolean valid = llmValidationGateway.validate(provider, session.getSelectedModel(), req.apiKey());
// And:   if (valid) {
//            session.setCredentialsValidated(true);
//            session.advance();

// ADD THESE TWO LINES before the return:
vault.storeSystemKey(VaultConstants.LLM_API_KEY, req.apiKey().getBytes(StandardCharsets.UTF_8));
// also store provider so runtime LlmModelFactory can pick the right one:
vault.storeSystemKey(VaultConstants.LLM_PROVIDER_KEY, provider.getBytes(StandardCharsets.UTF_8));
```

**Also:** Verify that `VaultConstants.LLM_API_KEY` and `VaultConstants.LLM_PROVIDER_KEY` constants exist in `arcclaw-core/src/main/java/com/arcclaw/core/security/VaultConstants.java`. Add any missing constants.

***

### Deliverable 3 — Persist `OnboardingSession` wizard state to SQLite

**Problem:** `OnboardingSession` is `@ApplicationScoped` (in-memory singleton). A Quarkus restart mid-wizard loses all state — user has to restart from step 1.

**File to create:** `arcclaw-onboarding/src/main/java/com/arcclaw/onboarding/OnboardingSession.java`

Refactor `OnboardingSession` to persist its state using the existing SQLite infrastructure (Hibernate ORM is already a test dependency — check if it's available at runtime in `arcclaw-app` or use raw `javax.sql.DataSource` / Agroal). Use a simple single-row keyed table:

```sql
CREATE TABLE IF NOT EXISTS onboarding_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

Keys: `current_step`, `selected_provider`, `selected_model`, `credentials_validated`.

Strategy:
- Keep the `@ApplicationScoped` bean but back reads/writes through the DB.
- On first access, check the DB; if no row exists, initialize defaults.
- Provide a `reset()` method that wipes all rows (called from `/complete` or future `/reset` endpoint).
- If adding a DB dependency to `arcclaw-onboarding` creates a circular dependency or is too heavy, use `SecretVault` (already injected) as the backing store instead — it already has `storeSystemKey` / `retrieveSystemKey`. Use keys prefixed with `onboarding.` (e.g., `onboarding.current_step`).

**Prefer the SecretVault approach** if SQLite/Hibernate is not already available at compile time in `arcclaw-onboarding`. It's simpler and avoids a new dependency.

***

### Deliverable 4 — Write `QuarkusTest` for the onboarding wizard flow

**File to create:** `arcclaw-onboarding/src/test/java/com/arcclaw/onboarding/OnboardingResourceTest.java`

Write a `@QuarkusTest` that exercises the full happy-path sequence using `RestAssured`. Mock `LlmValidationGateway` and `SecretVault` via `@InjectMock` / `@QuarkusMock`. Do NOT make real LLM API calls.

Required test cases:

```
1. GET /onboarding/status → returns step=1 on fresh start
2. POST /onboarding/step/1 → returns nextStep=2
3. POST /onboarding/step/2 with {"provider":"groq"} → returns nextStep=3, default model populated
4. POST /onboarding/step/3 with {"apiKey":"test-key"} → mocked gateway returns true → returns nextStep=4, credentialsValidated=true
5. POST /onboarding/step/4 with {"systemPrompt":"You are ArcClaw."} → returns nextStep=5
6. POST /onboarding/step/5 with {"mcpServers":[]} → returns nextStep=6
7. GET /onboarding/complete → returns {"done":true, "redirectTo":"/#/chat", ...}
8. Verify 403 if steps attempted out of order (e.g., POST /step/3 before /step/2)
```

Also add:
```java
// In src/test/resources/application.properties (create if missing):
quarkus.http.test-port=0
quarkus.shutdown.timeout=5S
%test.quarkus.datasource.db-kind=h2   // or sqlite in-memory if using DB in session
```

***

### Deliverable 5 — Verify `mvn test -pl arcclaw-onboarding` passes clean

After all the above:
1. Run `./mvnw test -pl arcclaw-onboarding -am` locally.
2. Confirm zero hanging processes — the surefire `forkedProcessExitTimeoutInSeconds=10` from P83A should enforce this.
3. Commit all changes with message: `feat(onboarding): harden wizard with vault storage, session persistence, and unit tests`

***

### Invariants (do NOT break these)
- `arcclaw-onboarding` must NOT depend on `arcclaw-app`. The dependency direction is always: `arcclaw-app` → `arcclaw-onboarding` → `arcclaw-core`.
- `LlmValidationGateway` stays in `arcclaw-core` as a pure interface. Do not move it.
- No Playwright, no frontend, no HTML/JS changes in this phase.
- All new classes must be in the `com.arcclaw.onboarding` package tree.
- Surefire config (`forkCount=1`, `reuseForks=true`, `forkedProcessExitTimeoutInSeconds=10`) stays intact.

***

### Definition of Done
- [ ] Root `pom.xml` includes `arcclaw-onboarding` module
- [ ] `step3` stores the API key AND provider to `SecretVault` on successful validation
- [ ] `VaultConstants` has `LLM_API_KEY` and `LLM_PROVIDER_KEY`
- [ ] `OnboardingSession` state survives a Quarkus restart (via SecretVault or SQLite)
- [ ] `OnboardingResourceTest` covers 8 test cases above, all green
- [ ] `./mvnw test -pl arcclaw-onboarding -am` completes without hanging