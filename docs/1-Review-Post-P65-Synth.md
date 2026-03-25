Please Note: This project tested very well.. works very fast and 99% accuracy of results in rest, telegram or chat.. tested accross all 3 env windows, linux (wsl2) and mac. 800+ testcases pass.

Got Reviews from all sort of LLMs.. Now you are final decider.. to do prioritization for Phase 66. I know this is huge task.. Trusted on you..  Please do your best to make this project the best.


---

# ArcClaw – Phase 66 Prioritization
**Final Synthesis Across All LLM Reviews**
**Date:** March 2026 | **Based On:** P65 codebase + 7 independent AI reviews

---

## Synthesis Verdict

P65 was the best phase yet — surgical, focused, and high-impact. It executed exactly on the two biggest P64 gaps: Embedding SPI (now **10/10**, textbook-perfect) and Channel Daemon SPI. The pattern that worked in P65 was: **identify the narrowest possible set of highest-impact changes and execute with precision.** P66 must follow the same discipline.

Overall P65 standing after reading all reviews and the full code:

| Dimension | P65 Score | Notes |
|---|---|---|
| RAG / Embedding SPI | ✅ 10/10 | Solved. Don't touch. |
| Notification / Cron SPI | ✅ 9/10 | Excellent. Stable. |
| Channel Daemon SPI | ✅ 8/10 | Done. `ChannelDaemon` interface + dynamic CDI discovery. |
| Channel Message Pipeline | ⚠️ 4.5/10 | Still Telegram-shaped. Core remaining debt. |
| LLM Provider Factory | ⚠️ 4/10 | 3-case switch. No SPI. Exact same pre-P65 state as embedding. |
| Agent Persona / Role | ⚠️ 3/10 | `AgentRoleEnum` is a hard lock on third-party persona plugins. |
| Governance | ⚠️ 3/10 | Concrete class only. No interface for pluggable billing/policy. |
| Test Coverage | ❌ 0/10 | The single undeniable gap across every reviewer. |
| Production Readiness | ✅ 9.5/10 | Already excellent. Audit, vault, rate limit, governance are solid. |

---

## Cross-Review Consensus Map

The table below shows what every reviewer flagged and my final call.

| Item | Grok | Gemini | Perplexity | DeepSeek | Qwen | Kimi | GLM | **My Call** |
|---|---|---|---|---|---|---|---|---|
| Zero test coverage | P1 | P1 | P1 | P1 | P1 | P0 | — | **P66-T1 (MUST)** |
| AgentRoleEnum → record/registry | — | **#1** | — | Medium | — | P1 | P2 | **P66-T2 (MUST)** |
| LlmProviderFactory SPI | P2 | — | P2 | Medium | — | — | — | **P66-T3 (MUST)** |
| Delete ApprovalTimeoutNotifier | P2 | P2 | P2 | P2 | P2 | — | — | **P66-T4 (MUST)** |
| ChannelPlugin SPI (composite) | **#1** | — | — | — | **#1** | P1 | **#1** | **P66-T5 (HIGH)** |
| TelegramMarkdownV2Renderer AST | — | **P2** | — | — | — | — | — | **P66-T6 (HIGH)** |
| GovernanceStrategy SPI | — | — | P2 | — | — | — | — | **P66-T7 (HIGH)** |
| sqlite-vec native auto-packaging | — | P3 | — | — | — | — | — | **P66-T8 (MEDIUM)** |
| channel-spi.md documentation | — | — | P3 | — | — | — | — | **P66-T9 (MEDIUM)** |
| Telegram module extraction | P1 | P1 | P1 | P1 | P1 | P1 | P1 | **P67 (DEFER — too large for P66)** |
| Flyway/Liquibase | — | — | — | — | P1 | — | — | **DEFER — not urgent** |
| SessionKnowledgeBase durability | — | — | — | — | P1 | — | — | **DEFER** |
| AgentLoop State Pattern | — | — | — | P0 | — | P1 | — | **DEFER** |
| Sandbox execution | — | — | P3 | — | — | — | — | **DEFER** |

---

## Where I Diverge From the Reviews

Three significant disagreements with the reviewer consensus:

**1. ChannelPlugin SPI is NOT P66's #1 priority** (disagrees with Grok, Qwen, GLM, Kimi)

Grok calls the ChannelPlugin SPI the single highest priority. I agree it is the biggest *architectural* gap, but three things make it wrong for P66's #1 slot: the channel already works perfectly in production; the ChannelDaemon SPI from P65 already handles daemon lifecycle; and the composite "ChannelPlugin" that bundles Sender + Renderer + Allowlist + Notifier + Daemon is a 3–5 day effort that should not crowd out the faster, higher-ROI wins. The right P66 call is to add the composite SPI interface (T5 below — a few new files) while deferring the full module extraction to P67.

**2. AgentRoleEnum → AgentRole is the architectural unlock for P66** (agrees with Gemini alone, disagrees with everyone else's ranking)

This is the exact `ChannelType enum → record` pattern that was the biggest win of P65. The code literally has `List<AgentRoleEnum> roles = List.of(CODER, DEBUGGER, RESEARCHER...)` in `IntentClassifierService` — the same three-case-switch anti-pattern that was eliminated for embeddings in P65. Converting this completes the "100% extensible SPI architecture" goal. Any third-party JAR wanting to add a `SecurityAuditorAgent` currently cannot without forking core. This fix is bounded, precise, and follows a pattern the codebase already executes perfectly.

**3. Tests come before all refactoring** (agrees with all, but this consensus is often ignored in practice)

Every reviewer listed tests as P1 and then proceeded to describe architectural refactors without establishing the test harness first. That is backwards. P66 should build the minimum test harness *before* the AgentRoleEnum refactor, so the refactor itself is validated by the tests it introduces.

---

## P66 Backlog — Prioritized

### T1 — Minimum Viable Test Harness (MUST — Do First)
**Effort:** 1.5–2 days | **Risk if skipped:** Every subsequent refactor is a blind leap on a complex ScopedValue + CDI system

The project has 800+ manual test cases but zero automated tests. This is the most dangerous gap for a framework with custom CDI scopes, async compaction, ScopedValues, and multi-channel pipeline. Do NOT start any architectural refactoring in P66 without this foundation.

**Minimum test set to write:**

```
WorkspaceContextBindingTest
  → WorkspaceContext.run() binds all ScopedValues correctly
  → Tools throw on unbound context
  → ScopedValue is NOT visible outside the binding call

EmbeddingProviderFactoryDispatchTest
  → Each of the 3 factories resolves by providerId
  → Unknown provider throws IllegalStateException (not silent fallback)

TwoTierMemoryMergeTest
  → RRF merge math is correct
  → Fast-path skip fires when vec0 disabled

ReflexRegistryDispatchTest
  → /approve, /deny, /status, /cron all dispatch to correct reflex
  → Unknown command falls through cleanly

ToolRegistryGuardTest
  → Blocked role cannot access SANDBOX_EXEC
  → Permitted role resolves correct tool set
```

These 5 test classes cover the highest-complexity paths and act as a regression net for every subsequent T2–T9 change.

---

### T2 — AgentRoleEnum → AgentRole record + AgentRoleRegistry (MUST)
**Effort:** 1 day | **Impact:** Completes "100% extensible SPI architecture"

This is the last hard lock on external extensibility. The pattern to follow is **exactly** what was done in P65 for `ChannelType` (enum → record) and `EmbeddingProviderFactory` (switch → SPI):

**Step 1 — Change `AgentRoleEnum` to an interface/record:**
```java
// Replace the enum
public record AgentRole(String id) {
    public static final AgentRole CODER        = new AgentRole("coder");
    public static final AgentRole DEBUGGER     = new AgentRole("debugger");
    public static final AgentRole RESEARCHER   = new AgentRole("researcher");
    public static final AgentRole COORDINATOR  = new AgentRole("coordinator");
    public static final AgentRole ARCHITECT    = new AgentRole("architect");
    public static final AgentRole SYNTHESIZER  = new AgentRole("synthesizer");
}
```

**Step 2 — Create `AgentRoleRegistry`** (mirrors `SkillRegistry`):
```java
@ApplicationScoped
public class AgentRoleRegistry {
    @Inject @Any Instance<Agent> agents;

    public Optional<Agent> resolve(AgentRole role) {
        return agents.stream()
            .filter(a -> a.role().equals(role.id()))
            .findFirst();
    }
    public List<AgentRole> allRoles() { ... }
}
```

**Step 3 — Update `IntentClassifierService`:**
Replace `List<AgentRoleEnum> roles = List.of(CODER, DEBUGGER...)` with `agentRoleRegistry.allRoles()`. This is the exact `Instance<EmbeddingProviderFactory>` pattern from P65.

**Step 4 — Update `SystemPromptBuilder`, `AgentLoop`, `AgentPersonaRegistry`:**
Replace all `AgentRoleEnum.fromString(agentRole)` calls with `AgentRoleRegistry.resolve(...)`.

**Outcome:** A third-party module can now add `new AgentRole("security-auditor")` + a CDI `Agent` bean with no core changes. The kernel becomes truly open.

---

### T3 — LlmProviderFactory SPI (MUST)
**Effort:** 0.5 day | **Impact:** Unlocks Anthropic, DeepSeek, Cohere without touching core

The `LlmModelFactory` is currently a 3-case switch (`groq`, `ollama`, `openai`). This is the identical pre-P65 state of `EmbeddingModelProducer`. The fix is mechanical — replicate the embedding pattern:

```java
// New SPI interface (mirrors EmbeddingProviderFactory exactly)
public interface LlmProviderFactory {
    String providerId();
    ChatModel create(LlmModelConfig config);
}

// New config record (mirrors EmbeddingModelConfig)
public record LlmModelConfig(
    String provider, String modelName, String baseUrl,
    Optional<String> apiKey, Duration timeout
) {}

// Three CDI beans replacing the switch:
// GroqLlmProviderFactory, OllamaLlmProviderFactory, OpenAiLlmProviderFactory

// Updated LlmModelFactory delegates via Instance<LlmProviderFactory>:
factories.stream()
    .filter(f -> f.providerId().equalsIgnoreCase(targetProvider))
    .findFirst()
    .orElseThrow(() -> new IllegalStateException("No LLM provider: " + targetProvider));
```

Adding Anthropic Claude or DeepSeek then requires zero core changes — drop in a new CDI bean JAR.

---

### T4 — Delete ApprovalTimeoutNotifier (MUST — Trivial)
**Effort:** 15 minutes | **Impact:** Removes dead code, reduces confusion

`ApprovalTimeoutNotifier` is `@ApplicationScoped @Deprecated` with an empty body. `ChannelApprovalTimeoutDispatcher` is the stable replacement and has been since P64. There is no reason this class still exists. Delete it. Update any import references.

---

### T5 — ChannelPlugin Composite SPI Interface (HIGH)
**Effort:** 0.5–1 day | **Impact:** Defines the formal plugin contract; unblocks Discord/Slack without full module extraction

Rather than the full Telegram module extraction (P67), create the composite `ChannelPlugin` interface that bundles all 5 channel contracts:

```java
// New interface in arcclaw-channel-api
public interface ChannelPlugin {
    ChannelType channelType();
    ChannelSender sender();
    ReplyRenderer renderer();
    ChannelAllowlist allowlist();
    ChannelJobNotifier jobNotifier();
    ChannelDaemon daemon();
}

// New TelegramChannelPlugin in app/channel/telegram:
@ApplicationScoped
public class TelegramChannelPlugin implements ChannelPlugin {
    @Inject TelegramChannelSender sender;
    @Inject TelegramMarkdownV2Renderer renderer;
    @Inject TelegramAllowlistGuard allowlist;
    @Inject TelegramJobNotifier notifier;
    @Inject TelegramChannelDaemon daemon;
    // ... implements all 6 methods
}

// New ChannelPluginRegistry in arcclaw-core (replaces ChannelSenderRegistry):
@ApplicationScoped
public class ChannelPluginRegistry {
    @Inject @Any Instance<ChannelPlugin> plugins;
    public Optional<ChannelPlugin> find(ChannelType type) { ... }
}
```

Adding Discord is now: create `DiscordChannelPlugin` implementing 6 methods, add the module. Zero changes to `arcclaw-app` or `arcclaw-core`. This is the contractual foundation. The physical module move to `arcclaw-channel-telegram` comes in P67.

---

### T6 — TelegramMarkdownV2Renderer → commonmark-java AST Parser (HIGH)
**Effort:** 0.5–1 day | **Risk if deferred:** Telegram 400 Bad Request errors as MCP tool output grows

The current `char-by-char esc()` in `TelegramMarkdownV2Renderer` will produce double-escaped output when MCP tools (GitHub, file readers) return pre-escaped Markdown strings. Telegram throws `HTTP 400: can't parse entities` on malformed MarkdownV2, which means agent responses silently fail to send.

**Fix:**
```xml
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
    <version>0.22.0</version>
</dependency>
```

```java
// Replace char-by-char esc() with AST visitor
public class TelegramMarkdownV2Renderer implements ReplyRenderer {
    private final Parser parser = Parser.builder().build();

    private String render(String markdown) {
        Node document = parser.parse(markdown);
        TelegramVisitor visitor = new TelegramVisitor();
        document.accept(visitor);
        return visitor.getResult();
    }
}

// TelegramVisitor implements NodeVisitor:
// visit(Heading) → bold escape
// visit(Code) → backtick format
// visit(Emphasis) → _ escape
// visit(StrongEmphasis) → ** escape
// visit(Text) → esc special chars in text nodes only
```

This parses Markdown structurally and only escapes special chars within text nodes, never double-escaping already-structured content.

---

### T7 — GovernanceStrategy SPI (HIGH)
**Effort:** 0.5 day | **Impact:** Enables per-tenant billing, custom policy engines

`GovernanceLayer` is a concrete `@ApplicationScoped` class. Extract the interface:

```java
// New interface
public interface GovernanceStrategy {
    void checkBudget(String sessionId, int tokens) throws BudgetExceededException;
    void recordCost(String sessionId, int inputTokens, int outputTokens);
    long getSessionCost(String sessionId);
}

// Existing GovernanceLayer becomes GovernanceLayer implements GovernanceStrategy
// (no behavioral change, just contract extraction)
```

This is a 30-minute extract-interface refactor with zero behavioral change. It enables future pluggable billing engines without any further architectural work.

---

### T8 — sqlite-vec Native Binary Auto-Packaging (MEDIUM)
**Effort:** 1 day | **Impact:** Major UX improvement; eliminates #1 adoption friction

Currently users must manually place `vec0.so/.dll/.dylib` at `ARCCLAW_SQLITE_VEC_PATH`. This is a significant barrier to adoption. The fix: bundle all three platform binaries in `src/main/resources/native/vec0/`:

```
src/main/resources/native/vec0/
  vec0-linux-x86_64.so
  vec0-macos-aarch64.dylib
  vec0-macos-x86_64.dylib
  vec0-windows-x86_64.dll
```

On startup:
```java
@ApplicationScoped
public class Vec0NativeLibraryInstaller {
    @Observes @Priority(100) StartupEvent ev; // Before RagSchemaInitializer

    void install() throws IOException {
        String os   = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String resource = resolveResourceName(os, arch); // e.g. "native/vec0/vec0-linux-x86_64.so"
        Path target = dataDir.resolve("lib/vec0" + extension(os));
        if (!Files.exists(target)) {
            try (InputStream in = getClass().getResourceAsStream("/" + resource)) {
                Files.copy(in, target);
            }
        }
        // Override ARCCLAW_SQLITE_VEC_PATH to target
    }
}
```

Result: zero-setup RAG vector search on all 3 platforms.

---

### T9 — channel-spi.md Documentation (MEDIUM)
**Effort:** 2 hours | **Impact:** Enables any external contributor to add a channel in < 1 hour

Create `docs/channel-spi.md` listing the 6-interface contract for a channel plugin after T5:

```markdown
# Adding a New Channel to ArcClaw

Implement `ChannelPlugin` by providing 6 CDI beans:
1. ChannelSender — sends List<ReplyNode> to the channel
2. ReplyRenderer — converts ReplyNode tree to channel-native format
3. ChannelAllowlist — determines if a ChannelUser is authorized
4. ChannelJobNotifier — sends cron job completion notifications
5. ChannelDaemon — lifecycle: isConfigured(), start()
6. ChannelPlugin — composite bean wiring the above 5

Config key: arcclaw.channel.<type>.token (or equivalent)
Example implementation: arcclaw-app/channel/telegram/
```

---

## P67 Preview — What is intentionally deferred

These items are real debt but the wrong scope for P66:

**arcclaw-channel-telegram module extraction** — Move all Telegram code to a separate Maven module. This is the #1 long-term architectural improvement (consensus across ALL 7 reviews) but it is a 3-day+ Maven restructure + large file move. It requires T5 (ChannelPlugin interface) to be stable first. Do this in P67 as the sole focus.

**AgentLoop State Pattern** — The God Class is real but works. The ScopedValue tests (T1) and AgentRole refactor (T2) are higher-ROI. Defer until P67/P68.

**Flyway/Liquibase migration** — Schema is manually managed but stable across 800+ test cases on 3 platforms. The `ALTER TABLE` logic in `CronSchemaEnforcer` is worth monitoring but not a breaking issue. Defer until there is an actual schema migration needed.

**SessionKnowledgeBase durability** — Valid concern (in-memory store loses context on crash before compaction). Low probability in practice given compaction triggers. P68.

**WorkspaceScopeHolder ThreadLocal PROPAGATION_BRIDGE removal** — Technically correct advice (removes the ThreadLocal bypass of ScopedValue), but risky to touch without the test harness solidly in place. Do after T1 is complete.

**Sandbox execution (SANDBOX_EXEC)** — Has been deferred since Phase 48. Remains P3.

---

## Recommended P66 Execution Order

```
Day 1–2:  T1 — Write the 5 test classes (WorkspaceContext, EmbeddingFactory,
                TwoTierMemory, ReflexRegistry, ToolRegistryGuard)

Day 3:    T4 — Delete ApprovalTimeoutNotifier (15 min)
          T2 — AgentRole record + AgentRoleRegistry + update IntentClassifierService,
               SystemPromptBuilder, AgentPersonaRegistry, AgentLoop

Day 4:    T3 — LlmProviderFactory SPI (mirror EmbeddingProviderFactory exactly)
          T7 — GovernanceStrategy interface extraction (30 min)

Day 5:    T5 — ChannelPlugin composite SPI + TelegramChannelPlugin + ChannelPluginRegistry
          T9 — channel-spi.md (2 hours)

Day 6:    T6 — TelegramMarkdownV2Renderer → commonmark-java AST parser
          T8 — sqlite-vec native binary auto-packaging

Day 7:    Run full 800+ test suite across Windows/Linux/Mac
          Write AgentRoleRegistryTest and LlmProviderFactoryTest (extend T1 harness)
```

---

## P66 Success Criteria

At the end of Phase 66, ArcClaw should satisfy ALL of the following:

- [ ] `mvn test` runs and passes at least 5 test classes covering ScopedValue, reflexes, tool guards, embedding dispatch, and memory merge
- [ ] A third-party JAR can add a new `AgentRole` + `Agent` CDI bean with zero changes to core
- [ ] A third-party JAR can add a new LLM provider with zero changes to core (mirrors embedding SPI)
- [ ] `ApprovalTimeoutNotifier` is deleted, not deprecated
- [ ] `ChannelPlugin` interface exists in `arcclaw-channel-api` with `TelegramChannelPlugin` as reference implementation
- [ ] `TelegramMarkdownV2Renderer` uses commonmark-java AST; no char-by-char esc() loop
- [ ] `GovernanceStrategy` interface exists; `GovernanceLayer` implements it
- [ ] sqlite-vec installs automatically from JAR resources on first run (all 3 platforms)
- [ ] `docs/channel-spi.md` exists with the 6-interface contract documented
- [ ] Overall extensibility score across all dimensions: **≥ 8.5/10**

---

## Final Word

P65 took ArcClaw from a very capable Telegram-first system to a genuinely extensible framework — RAG and notifications are now production-grade SPI. P66's mission is to complete the last two extensibility locks (agent roles + LLM providers) and establish the test harness that makes all future refactoring safe.

The channel module extraction is the right architectural endgame, but it deserves its own phase (P67) rather than being squeezed into P66 alongside role and LLM SPIs. The pattern that has worked — focused, surgical, bounded changes — is the pattern to continue.

After P66, ArcClaw will have achieved what no other Java agentic framework currently has: a fully extensible, SPI-first architecture where channels, embedding providers, LLM providers, agent personas, and governance policies can all be swapped via CDI beans without touching core. That is worth building carefully.

---
*Reviewed against: Grok, Gemini, Perplexity (Claude + GPT-5.4 + Gemini 3.1), DeepSeek, Qwen 3.5 Plus, Kimi K2.5, GLM 5 — P65 codebase (325 files, 6 Maven modules)*