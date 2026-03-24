The biggest wins will come from moving “pure logic” tests off `@QuarkusTest` and into plain JUnit, and using a lighter Quarkus profile where you still need the container.

### 1) Low-hanging fruit to convert to plain JUnit

Any test that:

- Only touches:
  - Prompt / template logic (`PromptTemplateEngine`, `SystemPromptBuilder`, `SkillSystemPrompt`, `SkillFrontmatter` parsing).  
  - JSON parsing / schema (`ReasoningStepParser`, `StructuredOutputEngine`, `PermissiveJsonExtractor`).  
  - Role / tool routing (`AgentRoleEnum`, `ToolCapability`, `SkillAwareIntentClassifier`, `SkillRegistry` lookups).  
  - Budget math (`StructuredRetryLoop`, `GovernanceLayer` budget thresholds).  
  - Reflex decision logic (`ToolInvocationGuard` decision, reflex matchers) without hitting REST or EntityManager.

can be made **plain JUnit**:

- Remove `@QuarkusTest`.  
- Instantiate the class directly, or with small test harnesses.  
- Use test doubles for collaborators (e.g., fake `SystemMetadataRegistry`, in-memory `SkillRegistry`).

Example: tests for `ReasoningStepParser` JSON error handling or `PromptTemplateEngine.templateFor()` switch coverage do not need Quarkus at all; they’re just Java + Jackson/Qute.

### 2) Keep `@QuarkusTest` only where truly needed

Keep Quarkus boot only for tests that must exercise:

- REST endpoints (`AgentResource`, `AuditResource`, MCP HTTP endpoints).  
- JPA / Hibernate ORM with real SQLite (`AgentCheckpoint` persistence, `AuditEventRepository` chain verification).  
- CDI scopes and Workspace context (`WorkspaceScoped` beans, `WorkspaceContext.run()` + REST).  
- MCP server integration (HTTP transport and actual wiring).

Everything else (business logic inside agents, reflexes, tools that don’t touch CDI/JPA) can be plain JUnit.

A quick classification heuristic:

- If the test asserts HTTP status, JSON response from `RestAssured`, or uses `@QuarkusTest` REST client → keep `@QuarkusTest`.  
- If the test only asserts Java object behavior, DTO mapping, or string output → move to plain JUnit.

### 3) Add a “minimal” test profile for app-level Quarkus tests

For the remaining `@QuarkusTest` classes in `arcclaw-app`, use a `@QuarkusTestProfile` that disables heavy features:

```java
public class MinimalAppTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.mcp.server.stdio.enabled", "false");
        cfg.put("quarkus.opentelemetry.enabled", "false");
        cfg.put("arcclaw.skills.hot-reload", "false");
        // if not testing external LLMs here:
        cfg.put("quarkus.langchain4j.openai.enabled", "false");
        cfg.put("quarkus.langchain4j.ollama.enabled", "false");
        return cfg;
    }
}
```

Then on app-level tests that just need API wiring:

```java
@QuarkusTest
@TestProfile(MinimalAppTestProfile.class)
public class ServeCommandTest { ... }
```

This avoids booting LLM providers, OTel, and stdio MCP for those tests.

### 4) Where to start for maximum payoff

Given 178 tests in `arcclaw-app`:

- Prioritize:
  - Agent reasoning tests.  
  - Prompt/skill tests.  
  - Reflex/guard tests.

Those are typically logic-heavy, I/O-light, and safe to move off Quarkus. If you convert even 30–40% of them to plain JUnit, you reduce both the perceived “per-test” cost and pressure on a single Quarkus boot.

If you’d like, tell me one or two specific test class names (e.g., `LangChainReasoningEngineTest`, `ToolInvocationGuardTest`), and I can sketch exactly how to rewrite one of them as a plain JUnit test harness.