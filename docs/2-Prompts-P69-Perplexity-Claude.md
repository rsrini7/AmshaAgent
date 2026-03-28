# 🔬 ArcClaw P68 — Updated Tier-1 Production Architecture Review

**Covers:** Full delta analysis P65 → P67 → P68. All previously issued P69-A/B/C prompts are superseded by this document.

***

## 1. EXECUTIVE SUMMARY

**Honest Maturity Assessment: "Rapidly Maturing — Two Confirmed Production Bugs Survive All Three Phases"**

P68 is the strongest version reviewed. It delivered 11 genuine architectural improvements from the P67 review: `ObservationTransformer` chain-of-responsibility, `InputSanitizer` SPI with CDI qualifiers, `SkillProvider` SPI, `LlmProviderFactory` SPI, `AgentRoleEnum` record with centralized `SKILL_PREFIX`, `Architecture_Invariants.md` as a living contract, `TruncationTransformer` at 16KiB, `RateLimitTracker`, `SessionReaper` for zombie recovery, `AgentExecutionContext` passed as an object (not decomposed params), and `SUSPICIOUSLY_SHORT` answer guard. 

The project is now structurally close to a top-tier agentic system. However, **two confirmed production bugs have survived all three phases without being fixed**, and one regression was introduced: `looksLikeNaturalLanguage()` got simpler and *more dangerous* in P68.

### Top 5 Critical Concerns (Updated)

| # | Concern | Status | Class |
|---|---------|--------|-------|
| 1 | `looksLikeNaturalLanguage()` now only checks `text.contains(" ")` — WORSE than P67 | **Regression** | `EngineStepHandler` |
| 2 | `⏳` sent BEFORE lock check — double-ack every time a user sends two messages | **Survived 3 phases** | `TelegramAgentHandler` |
| 3 | `WorkspaceContext.channelType()` returns `"TELEGRAM"` — now violates its own `Architecture_Invariants.md §1.4` | **Survived 3 phases** | `WorkspaceContext` |
| 4 | `extractUnavailableToolName()` compiles `Pattern.compile(...)` inline — violates Architecture_Invariants.md explicitly | **New in P68** | `EngineStepHandler` |
| 5 | `AuditHashUtil.getHmacKey()` returns internal `byte[]` by reference — key corruption on zero-out | **Survived 3 phases** | `AuditHashUtil` |

***

## 2. DEEP FINDINGS (P68 State)

### 2.1 What P68 Definitively Fixed

These are **closed** — do not re-raise in any future phase prompt:

| Fixed Item | How |
|---|---|
| SKILL_PREFIX DRY violation | `AgentRoleEnum.SKILL_PREFIX`, `isSkillRole()`, `skillName()` |
| Tool observation unbounded size | `TruncationTransformer` at 16KiB hard cap |
| PII/injection sanitizer scattered refs | `InputSanitizer` SPI with `@PII` / `@Injection` qualifiers |
| `ToolObservationSanitizer` monolith | Chain-of-responsibility via `ObservationTransformer` SPI |
| Param explosion in step handlers | `AgentExecutionContext` passed as record, all handlers updated |
| No architecture contract document | `Architecture_Invariants.md` — excellent addition |
| Zombie sessions on restart | `SessionReaper` with `onStartup` zombie recovery |
| Skill loading monolith | `SkillProvider` SPI with `ClasspathSkillProvider` + `DatabaseSkillProvider` |
| LLM provider hardcoded | `LlmProviderFactory` SPI with Groq/OpenAI/Ollama |
| "Done." non-answers surfaced to user | `SUSPICIOUSLY_SHORT` regex guard in runner |
| No rate-limit awareness | `RateLimitTracker` with provider backoff |

***

### 2.2 Confirmed Open Issues

**OPEN-1 — `looksLikeNaturalLanguage()` REGRESSION (P67 was bad; P68 is worse)**

- **Problem (P68 exact code):**
```java
private boolean looksLikeNaturalLanguage(String text) {
    if (text == null || text.isBlank()) return false;
    char first = text.stripLeading().charAt(0);
    if (first == '{' || first == '[') return false;
    return text.contains(" ");  // ← THE ONLY REMAINING CHECK
}
```
The P67 version at least checked for both space AND period. P68 removed the period check. Now any string containing a single space — including `"Rate limit exceeded for requests"`, `"Invalid API key detected"`, `"Tool execution error: permission denied"` — is classified as natural language and returned directly as the agent's **finished answer** to the user. 
- **Why it matters:** `looksLikeNaturalLanguage()` is called on the `failed_generation` content extracted from Anthropic tool_use_failed errors. This content is raw LLM error payload, not a user answer. The heuristic gate is the only thing preventing error text from surfacing as a final answer.
- **Real-world failure:** Anthropic API returns `tool_use_failed` with `failed_generation: "I cannot process this request due to content policy restrictions."` This has a space. The heuristic returns `true`. The agent's final answer to the user is Anthropic's content policy error message, word-for-word.

**OPEN-2 — Double-Acknowledgment Race (Survived All 3 Phases)**

- **Problem (P68 exact code in `TelegramAgentHandler`):**
```java
if (text != null && !text.trim().startsWith("/")) {
    sender.sendRaw(user, "⏳");                    // ← sent BEFORE lock
}
AtomicBoolean flag = processingFlags.get(chatId, id -> new AtomicBoolean(false));
if (!flag.compareAndSet(false, true)) {
    sender.sendRaw(user, "⏳ Still processing...");  // ← second message
    return;
}
```
When a user sends two messages while the first is processing: they receive `⏳` immediately, then `⏳ Still processing your previous message.` — two consecutive bot messages before any processing occurs. 
- **Real-world failure:** Every power user who types faster than the agent responds (which is everyone who asks follow-up questions) sees double acknowledgment spam. At 100 active users this becomes a significant UX defect and will generate support tickets.

**OPEN-3 — `WorkspaceContext.channelType()` Violates Its Own Architecture Invariants**

- **Problem:** `Architecture_Invariants.md §1.4` (added in P68) explicitly states: *"Any fallback to Telegram is LEGACY and should be removed, not copied."* Yet the same P68 codebase has:
```java
// Accessor with safe default — TELEGRAM is correct for all existing callers
public static String channelType() {
    return CHANNEL_TYPE.isBound() && CHANNEL_TYPE.get() != null
        ? CHANNEL_TYPE.get()
        : "TELEGRAM";   // ← violates §1.4 of the invariants file added THIS PHASE
}
```
The comment even says "TELEGRAM is correct for all existing callers" — which contradicts the invariant that says this is LEGACY. 
- **Real-world failure:** A REST or CLI call that does not bind `CHANNEL_TYPE` silently resolves to Telegram for any downstream logic (notification dispatch, plugin selection). The invariants document was written to forbid this exact pattern.

**OPEN-4 — `extractUnavailableToolName()` Compiles Pattern Inline**

- **Problem:** In `EngineStepHandler`:
```java
private String extractUnavailableToolName(String rawError) {
    Matcher m = Pattern
        .compile("attempted to call tool '([^']+)'")  // ← compiled fresh every call
        .matcher(rawError);
    return m.find() ? m.group(1) : null;
}
```
This method is called on every Groq `not in request.tools` error, which happens per-session for role mismatches. Pattern compilation is expensive and unnecessary. `Architecture_Invariants.md` itself (in the phase prompt template) says *"Use static final Pattern for any new regex — never compiled inline."* This violates the rule in the very file that defines it, in the same phase. 
- **Real-world failure:** Under high Groq usage with role-tool mismatches (the exact scenario this method handles), Pattern compilation runs on every request. On a JIT-cold start, this adds measurable latency and GC pressure.

**OPEN-5 — `AuditHashUtil.getHmacKey()` Returns Internal Byte Array**

- **Problem (P68 code unchanged from P67):** `getHmacKey()` returns `result` which is the same reference stored in `hmacKey`. No defensive copy. Any caller that zero-fills the returned array corrupts the master HMAC key for all subsequent audit computations. 
- **Real-world failure:** A security engineer adds `Arrays.fill(key, (byte) 0)` to `AuditVerifier.verify()` after use (standard JCA practice). Every `AuditEvent` after that moment has a wrong HMAC. The audit chain fails verification silently. Compliance systems flag the entire audit trail as tampered.

**OPEN-6 — `msg.getFrom()` NPE for Forwarded Telegram Messages**

- **Problem:** `String userId = String.valueOf(msg.getFrom().getId())` in `TelegramAgentHandler` — `getFrom()` is not null-checked. Telegram forwarded messages, channel posts, and bot-relay scenarios produce updates where `from` is null. 
- **Real-world failure:** A user forwards a message from a public channel into the bot chat. `NullPointerException` at `getFrom().getId()`. The exception propagates through `receiveMessage()` to Camel, which may re-deliver the message. Loop starts.

**OPEN-7 — `ReasoningStepParser.getFirst()` Drops All Parallel Tool Calls**

- **Problem (P68 unchanged):** `ToolExecutionRequest req = aiMessage.toolExecutionRequests().getFirst()` — drops all tool calls beyond the first when the LLM issues parallel tool use. 
- **Real-world failure:** Claude 3.5/GPT-4o returns two parallel tool calls. Only one executes. Agent reasons from incomplete observation state, producing hallucinated data synthesis.

**OPEN-8 — `RateLimitTracker` Is Global, Not Per-Provider**

- **Problem:** The single `AtomicReference<Instant> lastHit` tracks the last rate-limit hit across ALL LLM providers. `BACKOFF_SECONDS = 30` is hardcoded and not configurable. 
- **Why it matters:** A system configured with both Groq (for speed) and OpenAI (for fallback) uses a single rate limit signal. A Groq rate limit hit suppresses the compaction call on OpenAI for 30 seconds, even though OpenAI has no rate-limit issue. 
- **Real-world failure:** High-load Groq session triggers rate limit. Compaction is suppressed globally for 30 seconds. An OpenAI-backed session that needs compaction is blocked unnecessarily. Context window fills. Session degrades.

**OPEN-9 — `WorkspaceScopeHolder.PROPAGATION_BRIDGE` ThreadLocal Not Cleared in `processPipeline`**

- **Problem:** `TelegramAgentHandler.processPipeline()` finally block calls `requestContext.terminate()` but does NOT call `WorkspaceScopeHolder.clear()`. ManagedExecutor thread pool threads carry stale session IDs in the ThreadLocal bridge indefinitely. 
- **Real-world failure:** Under high concurrent load, thread pool threads accumulate stale bridge entries. ThreadLocal map grows. Subtle `@WorkspaceScoped` CDI bean resolution failures emerge when stale bridge interferes with active ScopedValue resolution.

**OPEN-10 — `REACT_KEYWORDS.contains(toolName.toUpperCase())` Missing `Locale.ROOT`**

- **Problem:** `toUpperCase()` without locale. Turkish "i" → "İ" (dotted uppercase I), which does not match "I" in ASCII comparison. "think" → "THİNK" in Turkish locale, which is NOT in `REACT_KEYWORDS`. 
- **Real-world failure:** System running on a Turkish-locale JVM (or a cloud provider whose JVM locale defaults to Turkish) silently fails to intercept ReAct keyword tool calls, allowing them through as real tool names and causing hallucination errors.

***

## 3. COMPARISON TABLE (P65 → P67 → P68)

| Dimension | P65 | P67 | P68 |
|---|---|---|---|
| Channel isolation | Telegram in app module | Telegram as own module | Same + `ChannelPlugin` SPI complete |
| Observation sanitization | Monolith, unbounded | Monolith, unbounded | Chain SPI, 16KiB cap ✅ |
| LLM providers | Hardcoded | SPI in app | SPI moved to core ✅ |
| Skill loading | Monolith | Monolith | `SkillProvider` SPI ✅ |
| Architecture contract | None | None | `Architecture_Invariants.md` ✅ |
| SKILL_PREFIX | Scattered x4 | Scattered x4 | Centralized in `AgentRoleEnum` ✅ |
| Double-ack bug | Present | Present | **Still present** ❌ |
| `channelType()` default | "TELEGRAM" | "TELEGRAM" | **"TELEGRAM" — now violates own invariants** ❌ |
| `looksLikeNaturalLanguage` | `contains(" ") && contains(".")` | Same | **Regressed to `contains(" ")` only** ❌ |
| Inline Pattern compile | Multiple | Multiple | Inline Pattern added in P68 ❌ |
| `AuditHashUtil` key exposure | Present | Present | **Still present** ❌ |
| Parallel tool calls | Dropped silently | Dropped silently | Dropped silently (WARN added in P68) ❌ |
| Zombie session recovery | None | None | `SessionReaper` ✅ |
| Answer quality guard | None | None | `SUSPICIOUSLY_SHORT` ✅ |
| Rate limiting | None | None | `RateLimitTracker` (global only) ⚠️ |

***

## 4. PRIORITIZED ROADMAP

### HIGH — P69-A (Before Any Production Traffic Increase)

| Title | Impact | Effort | Risk if Ignored |
|---|---|---|---|
| A1 — Fix `looksLikeNaturalLanguage()` regression | AI correctness | Low | Error text surfaces as answers on every Anthropic tool_use_failed |
| A2 — Fix double-ack in `TelegramAgentHandler` | UX correctness | Trivial | Every concurrent message pair produces 2 bot replies |
| A3 — Fix `WorkspaceContext.channelType()` Telegram default | Multi-channel + violates own invariants | Low | New channels silently route to Telegram plugin |
| A4 — Fix `extractUnavailableToolName` inline Pattern | Performance + own policy violation | Trivial | Pattern compiled on every Groq tool error |
| A5 — Fix `AuditHashUtil.getHmacKey()` key exposure | Security integrity | Trivial (1 line) | Key corruption destroys audit chain |
| A6 — Fix `msg.getFrom()` NPE | Stability | Low | Forwarded messages crash Camel route |

### MEDIUM — P69-B (Next Sprint)

| Title | Impact | Effort | Risk if Ignored |
|---|---|---|---|
| B1 — Add WARN log for dropped parallel tool calls (`ReasoningStepParser`) | Observability + correctness | Low | Silent data loss on Claude/GPT-4o parallel tool use |
| B2 — Add Telegram message chunking at 4096 chars | Reliability | Medium | Long responses silently dropped |
| B3 — Clear `WorkspaceScopeHolder` bridge in `processPipeline` finally | Threading | Low | ThreadLocal leak under high load |
| B4 — Fix `RateLimitTracker` to be per-provider | Correctness | Medium | Groq rate limits suppress OpenAI compaction |
| B5 — Add `@ConfigProperty` to `RateLimitTracker.BACKOFF_SECONDS` | Operability | Trivial | Cannot tune backoff without code change |

### LOW — P69-C (Backlog)

| Title | Impact | Effort | Risk if Ignored |
|---|---|---|---|
| C1 — `REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))` | Edge correctness | Trivial | Turkish locale JVM misses keyword detection |
| C2 — Delete `AgentStepProcessor` delegation shell | Maintainability | Low | Every handler change requires 3 files |
| C3 — `AgentLoop.run(goal, engine)` 2-arg overload orphans telemetry | Observability | Low | One-shot calls have no session in logs/spans |
| C4 — `AgentRoleEnum.fromString()` silent COORDINATOR fallback needs audit event | Observability | Low | Role misconfiguration invisible in production |

***

## 5. PHASE PROMPTS (UPDATED — SUPERSEDES ALL PREVIOUS)

***

```
PROMPT — PHASE 69-A: Correctness, Security & Policy-Compliance Fixes

Role: Senior Java Architect, Bug Fixer & Senior Java Developer
Task: Phase 69-A — Fix confirmed defects in live P68 production code.
All bugs below exist today in P68. No architectural changes. Surgical fixes only.

Architecture Context:
Refer @Architecture_Invariants.md (added in P68 — mandatory reading).

IMPLEMENTATION REQUIREMENTS

Common Requirements:
- Every fix must be accompanied by a test that proves the bug existed and now does not.
- No new dependencies. No structural package changes.
- All existing tests must remain green after every component.
- Use static final Pattern for any new regex — never compiled inline (per Architecture_Invariants.md).
- All fixes must be safe under JDK 25 (no preview features).
- Test commands: mvn clean verify -pl <module> (clean to prevent stale class false-negatives).

──────────────────────────────────────────────────────────────────
Component A1 — Fix looksLikeNaturalLanguage() Regression
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/EngineStepHandler.java

BUG (P68 code):
  private boolean looksLikeNaturalLanguage(String text) {
      if (text == null || text.isBlank()) return false;
      char first = text.stripLeading().charAt(0);
      if (first == '{' || first == '[') return false;
      return text.contains(" ");  // ← ONLY CHECK: any string with a space passes
  }

This means "Rate limit exceeded for requests", "Invalid API key detected",
"Tool execution error: permission denied" all return true and get surfaced
as the agent's finished answer to the user.

FIX — Replace the method entirely:
  private static final Pattern NATURAL_LANGUAGE_GATE =
      Pattern.compile("^[A-Za-z].*\\s.*[.!?]\\s*$", Pattern.DOTALL);

  private boolean looksLikeNaturalLanguage(String text) {
      if (text == null || text.length() < 20) return false;
      String trimmed = text.strip();
      // Reject structural data
      char first = trimmed.charAt(0);
      if (first == '{' || first == '[' || first == '<') return false;
      // Reject known error signal vocabulary
      String lower = trimmed.toLowerCase(Locale.ROOT);
      if (lower.contains("error") || lower.contains("exception") ||
          lower.contains("invalid") || lower.contains("failed") ||
          lower.contains("limit") || lower.contains("permission") ||
          lower.contains("unauthorized") || lower.contains("code\":")) return false;
      // Must end with sentence-closing punctuation AND have multiple tokens
      String[] tokens = trimmed.split("\\s+");
      return tokens.length >= 5 && NATURAL_LANGUAGE_GATE.matcher(trimmed).matches();
  }

TEST: EngineStepHandlerNaturalLanguageTest
Assert false: "Rate limit exceeded for requests"
Assert false: "Invalid API key detected"
Assert false: "Tool execution error: permission denied"
Assert false: "{"error": "Bad request", "code": 400}"
Assert false: "Done." (too short, no punctuation gate)
Assert true:  "The current deployment uses Kubernetes with three replicas."
Assert true:  "I analyzed the repository and found no critical vulnerabilities."

──────────────────────────────────────────────────────────────────
Component A2 — Fix Double-Acknowledgment in TelegramAgentHandler
──────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramAgentHandler.java

BUG (P68 code — same as P67, unfixed through 3 phases):
  if (text != null && !text.trim().startsWith("/")) {
      sender.sendRaw(user, "⏳");          // ← sent BEFORE lock check
  }
  AtomicBoolean flag = processingFlags.get(chatId, id -> new AtomicBoolean(false));
  if (!flag.compareAndSet(false, true)) {
      sender.sendRaw(user, "⏳ Still processing...");  // ← SECOND message
      return;
  }

When second message arrives while first is processing:
user receives ⏳ THEN "⏳ Still processing..." — two consecutive bot messages.

FIX — Move ⏳ to AFTER successful lock acquisition:
  AtomicBoolean flag = processingFlags.get(chatId, id -> new AtomicBoolean(false));
  if (!flag.compareAndSet(false, true)) {
      sender.sendRaw(user, "⏳ Still processing your previous message. Please wait.");
      return;
  }
  // Lock acquired — NOW send ⏳ acknowledgment
  if (text != null && !text.trim().startsWith("/")) {
      sender.sendRaw(user, "⏳");
  }

TEST: TelegramAgentHandlerConcurrencyTest
- Send message 1 for chatId "chat-1" (pipeline delayed artificially)
- Send message 2 for chatId "chat-1" immediately after
- Assert: exactly ONE ⏳ sent (for message 2, as rejection)
- Assert: message 2 response count == 1 ("Still processing")
- Assert: no double ⏳ before the rejection message

──────────────────────────────────────────────────────────────────
Component A3 — Fix WorkspaceContext.channelType() Telegram Default
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../scope/WorkspaceContext.java

BUG: Architecture_Invariants.md §1.4 states:
"Any fallback to Telegram is LEGACY and should be removed, not copied."
Yet channelType() returns "TELEGRAM" when unbound. This violates the
invariant document added in THIS SAME PHASE (P68).

FIX:

Step 1 — Replace channelType() with Optional-returning form:
  /**
   * Returns the bound channel type, or empty if not in a channel-bound context.
   * Do NOT add a Telegram default — see Architecture_Invariants.md §1.4.
   */
  public static Optional<String> channelType() {
      if (CHANNEL_TYPE.isBound() && CHANNEL_TYPE.get() != null) {
          return Optional.of(CHANNEL_TYPE.get());
      }
      return Optional.empty();
  }

Step 2 — Add a strict variant for code that must have a channel type:
  public static String requireChannelType() {
      return channelType().orElseThrow(() ->
          new IllegalStateException(
              "CHANNEL_TYPE not bound in WorkspaceContext. " +
              "This is a violation of Architecture_Invariants.md §1.4. " +
              "Ensure all channel entry points bind ChannelType via WorkspaceContext.run(..., channelType, ...)."));
  }

Step 3 — Migrate all call sites:
  - ChannelPluginRegistry usage: use requireChannelType()
  - Any notification dispatcher usage: use requireChannelType()
  - Logging-only usage: use channelType().orElse("UNBOUND")

TEST: WorkspaceContextChannelTypeTest
Assert: channelType() returns Optional.empty() when not bound
Assert: requireChannelType() throws IllegalStateException when not bound
Assert: message contains "Architecture_Invariants" in the exception (confirms link to policy)
Assert: both return correct value when bound via WorkspaceContext.run(..., ChannelType.TELEGRAM, ...)

──────────────────────────────────────────────────────────────────
Component A4 — Fix extractUnavailableToolName Inline Pattern Compilation
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/EngineStepHandler.java

BUG: Pattern.compile(...) inside a non-static method — compiled fresh on every call.
This violates Architecture_Invariants.md (phase prompt template rule explicitly
says "Use static final Pattern for any new regex — never compiled inline").

CURRENT (wrong):
  private String extractUnavailableToolName(String rawError) {
      Matcher m = Pattern
          .compile("attempted to call tool '([^']+)'")  // ← inline compile
          .matcher(rawError);
      return m.find() ? m.group(1) : null;
  }

FIX:
  private static final Pattern UNAVAILABLE_TOOL_PATTERN =
      Pattern.compile("attempted to call tool '([^']+)'");

  private String extractUnavailableToolName(String rawError) {
      Matcher m = UNAVAILABLE_TOOL_PATTERN.matcher(rawError);
      return m.find() ? m.group(1) : null;
  }

TEST: EngineStepHandlerUnavailableToolExtractionTest
Assert: extractUnavailableToolName("attempted to call tool 'manage_schedule' which was not in request.tools")
        == "manage_schedule"
Assert: extractUnavailableToolName("some other error") == null
Assert: the UNAVAILABLE_TOOL_PATTERN field is declared static final (compile-time check)

──────────────────────────────────────────────────────────────────
Component A5 — Fix AuditHashUtil.getHmacKey() Key Exposure
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../audit/AuditHashUtil.java

BUG: getHmacKey() returns the internal hmacKey byte[] reference directly.
Any caller that calls Arrays.fill(key, (byte) 0) — standard JCA practice —
corrupts the master key for all subsequent HMAC computations. No exception
is thrown. The audit chain silently produces wrong hashes.

CURRENT (wrong):
  private byte[] getHmacKey() {
      ...
      return result;   // ← returns internal reference
  }

FIX: Return a defensive copy:
  private byte[] getHmacKey() {
      byte[] result = hmacKey;
      if (result == null) {
          synchronized (this) {
              result = hmacKey;
              if (result == null) {
                  ...
                  hmacKey = result;
              }
          }
      }
      return result.clone();   // ← DEFENSIVE COPY — never expose internal reference
  }

TEST: AuditHashUtilKeyIsolationTest
Call getHmacKey() twice → key1, key2
Call Arrays.fill(key1, (byte) 0)
Assert: key2 is not zeroed (reference isolation)
Assert: subsequent hmacHex("test") produces a non-zero, consistent result

──────────────────────────────────────────────────────────────────
Component A6 — Fix msg.getFrom() NPE for Forwarded Messages
──────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramAgentHandler.java

BUG: String userId = String.valueOf(msg.getFrom().getId()) — no null check.
Telegram forwarded messages, channel posts, bot relays → getFrom() == null → NPE.

FIX: Add guard immediately after chatId is resolved and non-null:
  if (msg.getFrom() == null) {
      log.warnf("Dropped message with null 'from' in chat %s " +
                "(forwarded or channel post — no user identity available)", chatId);
      return;
  }
  String userId = String.valueOf(msg.getFrom().getId());

TEST: TelegramAgentHandlerNullFromTest
Create IncomingMessage where getChat() returns non-null but getFrom() returns null
Call receiveMessage()
Assert: no NullPointerException
Assert: sender.sendRaw() was not called
Assert: WARN log emitted with chatId
```

***

```
PROMPT — PHASE 69-B: Reliability, Threading & Observability Improvements

Role: Senior Java Architect & Senior Java Developer
Task: Phase 69-B — Fix reliability gaps, threading issues, and observability gaps.
No agent behavior changes. All tests must remain green.

Architecture Context:
Refer @Architecture_Invariants.md

Common Requirements:
- Accompany every change with a test.
- No new dependencies.
- mvn clean verify -pl <module> for all test runs.
- static final Pattern for any regex.

──────────────────────────────────────────────────────────────────
Component B1 — Add WARN Log for Dropped Parallel Tool Calls
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../reasoning/ReasoningStepParser.java

BUG: aiMessage.toolExecutionRequests().getFirst() silently drops
all tool calls beyond the first when LLMs issue parallel tool use.
Claude 3.5, GPT-4o, Gemini 2.0 all support parallel tool use.
The agent reasons from a corrupted partial-observation state.

NOTE: Full parallel execution is Phase 70+. This B1 fix makes the
data loss VISIBLE so monitoring can alert on it.

FIX:
  List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
  if (requests.size() > 1) {
      log.warnf("[PARALLEL-TOOLS] session=%s role=%s: LLM issued %d parallel tool calls. " +
                "Only first ('%s') will be executed. Calls 2..%d dropped. " +
                "Parallel tool execution is not yet supported — tracked for Phase 70.",
                sessionId, agentRole, requests.size(),
                requests.getFirst().name(), requests.size());
  }
  ToolExecutionRequest req = requests.getFirst();

TEST: ReasoningStepParserParallelToolTest
Mock ChatResponse with 3 ToolExecutionRequests
Call parse()
Assert: returned ReasoningStep uses first tool name
Assert: WARN log contains "parallel tool calls" and correct count
Assert: no exception

──────────────────────────────────────────────────────────────────
Component B2 — Add Telegram Message Chunking at 4096 Characters
──────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramChannelSender.java

BUG: Telegram rejects messages > 4096 characters with HTTP 400.
No chunking exists. Long agent responses (code reviews, research, summaries)
silently fail — user receives no response.

FIX — Add chunked send:
  private static final int TELEGRAM_MAX_LENGTH = 4096;

  private void sendRaw(String chatId, String text) {
      if (text == null || text.isBlank()) {
          log.warn("Attempted to send empty message to " + chatId + " — skipping");
          return;
      }
      if (text.length() <= TELEGRAM_MAX_LENGTH) {
          producerTemplate.sendBodyAndHeader(
              "telegram:bots", text, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
      } else {
          List<String> chunks = splitAtWordBoundary(text, TELEGRAM_MAX_LENGTH);
          log.infof("TELEGRAM-SEND splitting %d-char response into %d chunks for %s",
              text.length(), chunks.size(), chatId);
          for (String chunk : chunks) {
              producerTemplate.sendBodyAndHeader(
                  "telegram:bots", chunk, TelegramConstants.TELEGRAM_CHAT_ID, chatId);
          }
      }
  }

  private List<String> splitAtWordBoundary(String text, int maxLen) {
      List<String> parts = new ArrayList<>();
      while (text.length() > maxLen) {
          int split = text.lastIndexOf(' ', maxLen);
          if (split <= 0) split = maxLen;  // no space — hard cut
          parts.add(text.substring(0, split));
          text = text.substring(split).stripLeading();
      }
      if (!text.isBlank()) parts.add(text);
      return parts;
  }

TEST: TelegramChannelSenderChunkingTest
Send a 9000-character string
Assert: producerTemplate called exactly 3 times (9000 / 4096 = ceil 3)
Assert: no chunk exceeds 4096 characters

──────────────────────────────────────────────────────────────────
Component B3 — Clear WorkspaceScopeHolder Bridge in processPipeline
──────────────────────────────────────────────────────────────────

File: arcclaw-channel-telegram/.../TelegramAgentHandler.java

BUG: ManagedExecutor tasks may set PROPAGATION_BRIDGE via context propagation.
processPipeline() finally block calls requestContext.terminate() but does NOT
call WorkspaceScopeHolder.clear(). Bridge accumulates on pooled threads.

FIX: In processPipeline() finally block:
  } finally {
      WorkspaceScopeHolder.clear();  // ← ADD — clear bridge before returning thread to pool
      requestContext.terminate();
  }

TEST: TelegramAgentHandlerBridgeCleanupTest
Submit task via ManagedExecutor with bridge pre-set to "session-X"
After processPipeline() completes, assert WorkspaceScopeHolder.getIfActive() == null

──────────────────────────────────────────────────────────────────
Component B4 — Make RateLimitTracker Per-Provider With Configurable Backoff
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../governance/RateLimitTracker.java

BUG: Single global AtomicReference<Instant> tracks rate limits for ALL providers.
BACKOFF_SECONDS = 30 is hardcoded. A Groq rate limit suppresses OpenAI compaction.

FIX:
  @ApplicationScoped
  public class RateLimitTracker {

      @ConfigProperty(name = "arcclaw.governance.rate-limit-backoff-seconds",
                      defaultValue = "30")
      long backoffSeconds;

      // Per-provider tracking: key = provider name (e.g. "groq", "openai", "ollama")
      private final ConcurrentHashMap<String, AtomicReference<Instant>> hitsByProvider =
          new ConcurrentHashMap<>();

      public void recordHit(String provider) {
          hitsByProvider
              .computeIfAbsent(provider, p -> new AtomicReference<>(Instant.EPOCH))
              .set(Instant.now());
      }

      public boolean isBackingOff(String provider) {
          AtomicReference<Instant> ref = hitsByProvider.get(provider);
          if (ref == null) return false;
          return Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds));
      }

      /** Convenience: true if ANY provider is backing off (for compaction global check). */
      public boolean isAnyBackingOff() {
          return hitsByProvider.values().stream()
              .anyMatch(ref -> Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds)));
      }
  }

Migrate all existing callers of recordHit() / isBackingOff() to pass provider name.

TEST: RateLimitTrackerPerProviderTest
recordHit("groq")
Assert: isBackingOff("groq") == true
Assert: isBackingOff("openai") == false (not affected)
Assert: isAnyBackingOff() == true (global check still works)
After backoffSeconds + 1: Assert isBackingOff("groq") == false
```

***

```
PROMPT — PHASE 69-C: Low-Priority Hygiene & Locale Safety

Role: Senior Java Developer
Task: Phase 69-C — Targeted hygiene fixes. No behavior changes.
All tests must remain green. mvn clean verify for all modules.

──────────────────────────────────────────────────────────────────
Component C1 — Fix REACT_KEYWORDS Locale Safety
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/ToolStepHandler.java

FIX:
  REACT_KEYWORDS.contains(toolName.toUpperCase())
→ REACT_KEYWORDS.contains(toolName.toUpperCase(Locale.ROOT))

Import: import java.util.Locale;

TEST: Assert "think" matches REACT_KEYWORDS in a Turkish-locale test context.

──────────────────────────────────────────────────────────────────
Component C2 — Delete AgentStepProcessor Delegation Shell
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/AgentStepProcessor.java (DELETE)
      arcclaw-core/.../loop/AgentStateMachineRunner.java (MODIFY)

ACTION:
1. Delete AgentStepProcessor.java
2. In AgentStateMachineRunner, inject EngineStepHandler, ToolStepHandler,
   ApprovalCheckpointManager directly.
3. Replace all stepProcessor.* calls with direct handler calls.

TEST: All existing AgentStateMachineRunner tests pass unchanged.

──────────────────────────────────────────────────────────────────
Component C3 — Emit Audit Event on Unknown Role Silent COORDINATOR Fallback
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/AgentLoop.java

BUG: When agentRole is unknown, silently runs as COORDINATOR with only log.warnf().
No audit event. No metric. Invisible in production.

FIX: Inject AuditService and add:
  auditService.record(sessionId, WorkspaceContext.userId(), agentRole,
      SecurityEventType.INVALID_ROLE_FALLBACK,
      "Unknown agentRole '" + agentRole + "' — defaulted to COORDINATOR");

Also add a Micrometer counter:
  Counter.builder("arcclaw.agent.role.unknown")
      .tag("role", agentRole)
      .register(meterRegistry)
      .increment();

TEST: AgentLoopUnknownRoleFallbackAuditTest
Call run() with role "completely-unknown-role"
Assert: result is from COORDINATOR (not exception)
Assert: AuditService.record() called with INVALID_ROLE_FALLBACK
Assert: counter incremented

──────────────────────────────────────────────────────────────────
Component C4 — Add Null Validation to AgentExecutionContext Compact Constructor
──────────────────────────────────────────────────────────────────

File: arcclaw-core/.../loop/AgentExecutionContext.java

FIX: Add compact constructor validation:
  public AgentExecutionContext {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(agentRole,  "agentRole must not be null");
      Objects.requireNonNull(goal,       "goal must not be null");
      if (maxIterations <= 0)
          throw new IllegalArgumentException(
              "maxIterations must be > 0, got: " + maxIterations);
  }

TEST: Assert null sessionId → NullPointerException with "sessionId" in message.
      Assert maxIterations = 0 → IllegalArgumentException.
```

***

**Final honest verdict on ArcClaw P68:** The architectural trajectory from P65 to P68 is excellent — the codebase is improving in exactly the right directions with the right patterns. The `Architecture_Invariants.md` addition is the single best thing done in any phase. But it creates an obligation: every invariant in that document must be enforced in code, not just documented. Right now, three violations of the invariants exist in the same P68 codebase that introduced them. Fix the six A-items above and ArcClaw becomes a genuinely Tier-1-ready agentic platform.