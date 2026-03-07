**Refactor: Replace fully-qualified class references with import statements**

Scan all Java files in `arcclaw-core` and `arcclaw-app` for every pattern where a fully-qualified name is written inline instead of being declared as a top-level import. No logic changes — purely mechanical import extraction.

**Patterns to detect and fix:**

1. **Field declarations and local variables**
```java
// BEFORE
private java.util.regex.Pattern pattern = ...;
java.util.Set<String> allowedTools = ...;

// AFTER
import java.util.regex.Pattern;
import java.util.Set;
```

2. **Return types and method parameters**
```java
// BEFORE
public java.util.List<String> getTools() { ... }

// AFTER
import java.util.List;
```

3. **Object instantiation**
```java
// BEFORE
return new com.arcclaw.core.agent.loop.ToolInvocationGuard.GuardDecision.Allow();

// AFTER
import com.arcclaw.core.agent.loop.ToolInvocationGuard;
// use: new ToolInvocationGuard.GuardDecision.Allow()
```

4. **Fully-qualified static method calls**
```java
// BEFORE
java.util.Collections.singletonList(...)
java.util.Arrays.stream(...)

// AFTER
import java.util.Collections;
import java.util.Arrays;
// use: Collections.singletonList(...), Arrays.stream(...)
```
Do NOT convert to `import static` unless it was already `import static` — preserve original call style.

5. **Inline annotations**
```java
// BEFORE
@com.fasterxml.jackson.annotation.JsonProperty("name")
@jakarta.validation.constraints.NotNull

// AFTER
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
```

6. **Generic type parameters**
```java
// BEFORE
Map<String, com.arcclaw.core.agent.result.AgentResult> results;

// AFTER
import com.arcclaw.core.agent.result.AgentResult;
```

7. **Cast expressions**
```java
// BEFORE
return (com.arcclaw.core.audit.ComplianceViolationException) cause;

// AFTER
import com.arcclaw.core.audit.ComplianceViolationException;
```

**Rules:**
- Only fix inline usage inside class bodies — do NOT touch the `package` declaration line
- Add imports at the top of the file in the existing import block, sorted alphabetically within groups: `java.*`, `jakarta.*`, `dev.*`, `com.*`
- Do NOT add duplicate imports if the class is already imported
- Do NOT touch `import static` declarations — leave those as-is
- If a fully-qualified name is used to disambiguate two classes with the same simple name in the same file, leave it fully qualified and add a comment `// fully qualified to disambiguate`
- Do NOT change string literals that happen to contain package paths (e.g. log messages, annotations with class name strings)

**Execution order — compile after each package, not after all files:**
1. `com.arcclaw.core.agent.loop` → compile
2. `com.arcclaw.core.agent.reasoning` → compile
3. `com.arcclaw.core.agent.persona` → compile
4. `com.arcclaw.core.agent.role` → compile
5. `com.arcclaw.core.agent.result` → compile
6. `com.arcclaw.core.audit` → compile
7. `com.arcclaw.core.lifecycle` → compile
8. All remaining `com.arcclaw.core.*` packages → compile
9. `arcclaw-app` module → compile
10. Final `./mvnw clean test` — all tests must pass green

**Definition of done:**
- Zero fully-qualified inline class references remain in any `.java` file across both modules (excluding `package` declarations and string literals)
- No duplicate imports introduced
- No `import static` conversions
- All existing tests pass unchanged