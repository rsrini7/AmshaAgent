This is the **final merged, definitive Phase 67 plan** — adjudicated from the Claude.ai prompts, Grok's challenges, and my own P66 code review. The verdict is below before the prompts. 

***
## Grok vs Claude — Final Perplexity Verdict
| Grok Claim | Verdict |
|---|---|
| `ChannelMessageHandler` in `ChannelPlugin` — do during T1 | **Claude wins.** `ChannelDaemon.start()` already IS the inbound SPI hook. Adding `messageHandler()` to `ChannelPlugin` would Camel-couple the shared API contract. Fix: document this clearly in `CHANNEL_SPI.md` update. |
| MCP into `ChannelPlugin` | **Both agree: Wrong.** MCP is tool-layer. Channel and tool planes are orthogonal. Skip entirely. |
| `GovernanceStrategy` missing | **Claude correct.** Grok reviewed stale P65 code. It landed cleanly in P66-T7. |
| `ToolInvocationStrategy` is P67 | **Grok right on substance, Claude right on timing.** Real smell in `ToolInvoker` (3 SRP violations). Document as `@Architecture_Invariants.md` #39 for P68. Do NOT do in P67. |

**P67 consensus task order: T1→T3→T2→T4→T5→T6. Six tasks, clean sequence, each independently green.**

***
# PHASE 67 — FINAL COMPLETE PROMPTS

Note : @Architecture_Invariants.md if missed specific section can refer @Architecture_Nuances.md (Invariants created from Nuances.)
---
## PROMPT T67-1: Extract `arcclaw-channel-telegram` Maven Module
```
You are implementing Phase 67-T1 for ArcClaw — Quarkus 3.27.2 / JDK 25 / LangChain4j.
Role: Senior Java Architect.

GOAL
Move all Telegram-specific classes out of arcclaw-app into a new arcclaw-channel-telegram
Maven module. ChannelPlugin SPI (P66-T5) is the stable contract. After this task,
arcclaw-app must contain zero Telegram imports.

ARCHITECTURE NUANCES (MUST FOLLOW)
- Nuance #5: Quarkus CDI beans in library modules need beans.xml AND jandex-maven-plugin
  to be index-discovered. Without it, @ApplicationScoped beans are invisible to Quarkus.
- Nuance #6: Every new Maven module must be listed in the root pom.xml <modules> block
  in dependency order (channel-telegram depends on channel-api and core, so it goes after them).
- Nuance #22: ChannelDaemon.start() IS the inbound SPI for channel listening.
  Do NOT add ChannelMessageHandler to ChannelPlugin — that would Camel-couple the
  channel-api contract. TelegramChannelRoute (a Camel RouteBuilder) is Telegram-specific and
  lives correctly inside the new channel-telegram module.

=============================================================
STEP 1: Create arcclaw-channel-telegram/pom.xml
=============================================================

<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.arcclaw</groupId>
    <artifactId>arcclaw-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>arcclaw-channel-telegram</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.arcclaw</groupId>
      <artifactId>arcclaw-channel-api</artifactId>
    </dependency>
    <dependency>
      <groupId>com.arcclaw</groupId>
      <artifactId>arcclaw-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-telegram</artifactId>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-bean</artifactId>
    </dependency>
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.apache.camel.quarkus</groupId>
      <artifactId>camel-quarkus-junit5</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- MANDATORY: Without Jandex, CDI beans are invisible to Quarkus -->
      <plugin>
        <groupId>io.smallrye</groupId>
        <artifactId>jandex-maven-plugin</artifactId>
        <version>3.5.3</version>
        <executions>
          <execution>
            <id>make-index</id>
            <goals><goal>jandex</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>

=============================================================
STEP 2: Add to root pom.xml <modules> block
=============================================================
Insert BEFORE arcclaw-app (channel-telegram depends on core and channel-api):
  <module>arcclaw-channel-telegram</module>

=============================================================
STEP 3: Create arcclaw-channel-telegram/src/main/resources/META-INF/beans.xml
=============================================================
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
       https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
       bean-discovery-mode="annotated" version="4.0"/>

=============================================================
STEP 4: Move files — exact list
=============================================================

FROM: arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/
TO:   arcclaw-channel-telegram/src/main/java/com/arcclaw/channel/telegram/

Package change: com.arcclaw.app.channel.telegram → com.arcclaw.channel.telegram

Files to move (9 total):
  TelegramAgentHandler.java
  TelegramChannelDaemon.java
  TelegramChannelPlugin.java
  TelegramChannelRoute.java
  TelegramChannelSender.java
  TelegramConstants.java
  TelegramJobNotifier.java
  TelegramMarkdownV2Renderer.java
  TelegramMessageMapper.java

FROM: arcclaw-app/src/main/java/com/arcclaw/app/security/
TO:   arcclaw-channel-telegram/src/main/java/com/arcclaw/channel/telegram/

File to move (1):
  TelegramAllowlistGuard.java
  (Package change: com.arcclaw.app.security → com.arcclaw.channel.telegram)

FROM: arcclaw-app/src/main/java/com/arcclaw/app/config/
TO:   arcclaw-channel-telegram/src/main/java/com/arcclaw/channel/telegram/config/

File to move (1):
  ChannelReflectionConfig.java
  (Only if it exclusively registers Telegram classes. If mixed, split it.)

=============================================================
STEP 5: Move test files
=============================================================

FROM: arcclaw-app/src/test/.../app/channel/telegram/
TO:   arcclaw-channel-telegram/src/test/.../channel/telegram/

  TelegramAgentHandlerTest.java
  TelegramChannelRouteStartupTest.java
  TelegramChannelRouteTest.java
  TelegramChannelSenderTest.java
  TelegramMarkdownV2RendererTest.java

FROM: arcclaw-app/src/test/.../app/channel/
TO:   arcclaw-channel-telegram/src/test/.../channel/telegram/

  AgentDispatchHandlerAutonomyTest.java
  ChannelDaemonSpiTest.java

Create: arcclaw-channel-telegram/src/test/resources/application.yaml
  (copy relevant Telegram test config from arcclaw-app test application.yaml)

=============================================================
STEP 6: Update arcclaw-app/pom.xml
=============================================================

REMOVE these dependencies (they move to arcclaw-channel-telegram):
  camel-quarkus-telegram
  camel-quarkus-bean

ADD:
  <dependency>
    <groupId>com.arcclaw</groupId>
    <artifactId>arcclaw-channel-telegram</artifactId>
  </dependency>

=============================================================
STEP 7: Update arcclaw-app application.yaml
=============================================================

Add to quarkus.index-dependency:
  arcclaw-channel-telegram:
    group-id: com.arcclaw
    artifact-id: arcclaw-channel-telegram

=============================================================
STEP 8: Update all package references
=============================================================

Run this after the move to find remaining old references:
  rg "com\.arcclaw\.app\.channel\.telegram\|com\.arcclaw\.app\.security\.TelegramAllowlist" \
     arcclaw-app/src/main/java --type java

Expected: ZERO results. Fix any that appear.

=============================================================
STEP 9: Update CHANNEL_SPI.md
=============================================================

Add a section clarifying the inbound contract:

## Inbound Message Handling
ArcClaw's inbound contract uses ChannelDaemon, NOT ChannelPlugin:
- ChannelDaemon.start() sets up your transport listener (Camel route, WebSocket, etc.)
- Your listener converts the raw inbound transport event into a ChannelMessage
- Call MessagePipeline.process(channelMessage) — the pipeline is 100% channel-agnostic

For Telegram, TelegramChannelRoute (Camel RouteBuilder) IS the ChannelDaemon's listener.
For Discord, you would create DiscordChannelDaemon.start() which registers a JDA listener.
MessagePipeline.process() is identical for all channels.

This design keeps Camel (and all other transport libs) OUT of arcclaw-channel-api.

=============================================================
STEP 10: Add boundary enforcement test to arcclaw-app
=============================================================

File: arcclaw-app/src/test/java/com/arcclaw/app/ModuleBoundaryTest.java

@QuarkusTest
class ModuleBoundaryTest {

    @Test
    void arcclaw_app_must_not_contain_telegram_classes() {
        assertThatThrownBy(() ->
            Class.forName("com.arcclaw.app.channel.telegram.TelegramChannelPlugin"))
            .isInstanceOf(ClassNotFoundException.class)
            .describedAs("TelegramChannelPlugin must live in arcclaw-channel-telegram, not arcclaw-app");
    }

    @Test
    void arcclaw_app_must_not_contain_telegram_allowlist_guard() {
        assertThatThrownBy(() ->
            Class.forName("com.arcclaw.app.security.TelegramAllowlistGuard"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}

=============================================================
STEP 11: Verification
=============================================================

# Zero Telegram imports in arcclaw-app main sources (must return 0):
rg "com\.arcclaw\.app\.channel\.telegram\|TelegramAgent\|TelegramChannel\|TelegramJob\|TelegramMarkdown\|TelegramMessage\|TelegramAllowlist" \
   arcclaw-app/src/main/java --type java

# Build the new module in isolation:
mvn clean test -pl arcclaw-channel-telegram

# Full suite must be green:
mvn clean test

SUCCESS CRITERIA
- rg "com.arcclaw.app.channel.telegram" arcclaw-app/src/main/java → 0 results
- All 810+ tests pass
- TelegramChannelPlugin discovered by ChannelPluginRegistry via CDI (verify in ChannelPluginRegistryTest)
- Manual Telegram bot sends and receives messages correctly
```

***
## PROMPT T67-2: PromptTemplateEngine — Explicit Fallback Warning
```
You are implementing Phase 67-T2 for ArcClaw — Quarkus 3.27.2 / JDK 25.
Role: Senior Java Developer. Minimal change, maximum correctness.

GOAL
PromptTemplateEngine.templateFor() silently falls back to coordinatorTemplate for any
AgentRole not in the built-in 6. Third-party roles get coordinator tool rules with no
warning — this is silent wrong behavior. Replace silence with explicit logging.
Also fix the same silent fallback in AgentPersonaRegistry.getPersonaPrompt().

NO BEHAVIORAL CHANGE FOR EXISTING TESTS. This is a logging fix, not a logic fix.

=============================================================
CHANGE 1: PromptTemplateEngine.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/PromptTemplateEngine.java

Locate the templateFor(AgentRoleEnum role) method. Find the switch default case.

BEFORE:
  default -> coordinatorTemplate;

AFTER:
  default -> {
      log.warnf("[PromptTemplateEngine] No Qute template registered for role '%s'. " +
                "Falling back to coordinator tool rules. " +
                "To fix: create templates/prompts/role-%s.txt in arcclaw-core/src/main/resources. " +
                "The coordinator tool ruleset includes GitHub, memory-search, schedule, and manage-schedule.",
                role.id(), role.id());
      yield coordinatorTemplate;
  }

Add logger if not already present:
  private static final Logger log = Logger.getLogger(PromptTemplateEngine.class);

=============================================================
CHANGE 2: AgentPersonaRegistry.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/agent/persona/AgentPersonaRegistry.java

Find the getPersonaPrompt() method. At the fallback return site:

BEFORE (approximate current code):
  // Falls back to coordinator persona silently
  return coordinatorPrompt;

AFTER:
  log.warnf("[AgentPersonaRegistry] No Agent bean registered for role '%s'. " +
            "Returning coordinator persona as fallback. " +
            "To fix: create a @ApplicationScoped class implementing Agent with role() = '%s'.",
            role.toScopedName(), role.toScopedName());
  // then return coordinator persona or the default string

=============================================================
CHANGE 3: Tests
=============================================================

File: arcclaw-core/src/test/java/com/arcclaw/core/agent/reasoning/PromptTemplateEngineTest.java
(or SystemPromptBuilderTest.java — whichever already covers buildSystemPrompt)

Add two tests:

@Test
void unknownDynamicRoleGetsCoordinatorToolRulesAndDoesNotThrow() {
    // AgentRoleEnum.fromString("security-auditor") returns an open role
    AgentRoleEnum unknown = AgentRoleEnum.fromString("security-auditor");
    // Must NOT throw — must return non-blank content
    String prompt = promptTemplateEngine.buildSystemPrompt(unknown, null, Instant.now());
    assertThat(prompt).isNotBlank();
    // Must contain coordinator tool rules as fallback evidence
    assertThat(prompt).containsIgnoringCase("PERMITTED TOOLS");
}

@Test
void allSixBuiltInRolesResolveToTheirOwnTemplate() {
    // None of the built-in 6 should trigger the WARN path
    for (AgentRoleEnum role : AgentRoleEnum.values()) {
        if (!role.isKnownRole()) continue;
        assertThatNoException()
            .isThrownBy(() -> promptTemplateEngine.buildSystemPrompt(role, null, Instant.now()));
    }
}

=============================================================
VERIFICATION
=============================================================

mvn clean test -pl arcclaw-core -Dtest="PromptTemplateEngineTest,SystemPromptBuilderTest,AgentPersonaRegistryTest"

SUCCESS CRITERIA
- Zero compilation errors
- Unknown role → log line contains "[PromptTemplateEngine]" + role name
- No NPE or IllegalArgumentException for any AgentRoleEnum value
- All 810+ tests pass
```

***
## PROMPT T67-3: Retire `ChannelSenderRegistry` + Fix Approval Timeout Bias
```
You are implementing Phase 67-T3 for ArcClaw — Quarkus 3.27.2 / JDK 25.
Role: Senior Java Developer. Two deletions in one task.

GOAL A: Delete ChannelSenderRegistry — ChannelPluginRegistry (P66-T5) supersedes it.
GOAL B: Remove the .orElse(ChannelType.TELEGRAM) silent bias in ChannelApprovalTimeoutDispatcher.

=============================================================
STEP 1: Audit all ChannelSenderRegistry callers
=============================================================

rg "ChannelSenderRegistry" --type java -n

For EACH caller, replace the pattern:

BEFORE:
  @Inject ChannelSenderRegistry senderRegistry;
  ChannelSender sender = senderRegistry.findSender(channelType)
      .orElseThrow(...);

AFTER:
  @Inject ChannelPluginRegistry pluginRegistry;
  ChannelSender sender = pluginRegistry.find(channelType)
      .map(ChannelPlugin::sender)
      .orElseThrow(() -> new IllegalStateException(
          "No ChannelPlugin registered for type: " + channelType));

For dispatchers that call jobNotifier:

BEFORE:
  senderRegistry.findSender(channelType).ifPresent(s -> s.sendRaw(user, msg));

AFTER:
  pluginRegistry.find(channelType)
      .map(ChannelPlugin::jobNotifier)
      .ifPresentOrElse(
          n -> n.notifyJobComplete(event),
          () -> log.warnf("No ChannelPlugin for channelType [%s] — dropping notification", channelType));

=============================================================
STEP 2: Fix ChannelApprovalTimeoutDispatcher — remove silent Telegram bias
=============================================================

File: arcclaw-app/src/main/java/com/arcclaw/app/channel/ChannelApprovalTimeoutDispatcher.java

BEFORE (locate this pattern):
  ChannelType type = ChannelType.fromString(event.channelType())
      .orElse(ChannelType.TELEGRAM);  // ← silent wrong default

AFTER:
  Optional<ChannelType> typeOpt = ChannelType.fromString(event.channelType());
  if (typeOpt.isEmpty()) {
      log.warnf("ChannelApprovalTimeoutDispatcher: unknown channelType '%s' for session %s — " +
                "dropping timeout notification. Fix: ensure channelType is set on AgentCronEntity.",
                event.channelType(), event.sessionId());
      return;
  }
  ChannelType type = typeOpt.get();

=============================================================
STEP 3: Delete ChannelSenderRegistry.java
=============================================================

After ALL callers are updated and building successfully:

DELETE: arcclaw-app/src/main/java/com/arcclaw/app/channel/ChannelSenderRegistry.java

Verify deletion:
  rg "ChannelSenderRegistry" --type java
  # Must return 0 results

=============================================================
STEP 4: Tests
=============================================================

File: arcclaw-app/src/test/java/com/arcclaw/app/channel/ChannelSenderRegistryRetirementTest.java

class ChannelSenderRegistryRetirementTest {

    @Test
    void channelSenderRegistryMustNotExist() {
        assertThatThrownBy(() ->
            Class.forName("com.arcclaw.app.channel.ChannelSenderRegistry"))
            .isInstanceOf(ClassNotFoundException.class)
            .describedAs("ChannelSenderRegistry was retired in P67-T3. Use ChannelPluginRegistry.");
    }
}

File: (modify existing) ChannelApprovalTimeoutDispatcherTest or create new:

@Test
void unknownChannelTypeDropsNotificationWithWarnAndDoesNotThrow() {
    // Fire an ApprovalTimedOutEvent with channelType = "DISCORD" (no plugin registered)
    // Verify: no exception thrown, log contains the dropping message
    // Verify: no ChannelSender.sendRaw() was called
}

@Test
void telegramApprovalTimeoutUsesPluginRegistryNotHardcodedType() {
    // Fire with channelType = "TELEGRAM"
    // Verify: pluginRegistry.find(TELEGRAM) is called, not ChannelType.TELEGRAM hardcoded
}

=============================================================
VERIFICATION
=============================================================

rg "ChannelSenderRegistry" --type java          # must return 0
rg "orElse(ChannelType.TELEGRAM)" --type java   # must return 0
mvn clean test -pl arcclaw-app
mvn clean test   # full suite

SUCCESS CRITERIA
- ChannelSenderRegistry.java does not exist
- ChannelApprovalTimeoutDispatcher uses Optional, not orElse(TELEGRAM)
- ChannelCronNotificationDispatcher routes via ChannelPluginRegistry
- All 810+ tests pass
```

***
## PROMPT T67-4: `processingFlags` → Caffeine Expiring Cache
```
You are implementing Phase 67-T4 for ArcClaw — Quarkus 3.27.2 / JDK 25.
Role: Senior Java Developer & Bug Fixer.

GOAL
TelegramAgentHandler.processingFlags is a ConcurrentHashMap<String, AtomicBoolean>.
It has TWO bugs:
  BUG 1: Memory leak — entries are never removed for chats that stop messaging.
          A bot running for months accumulates all historical chatIds.
  BUG 2: Race condition — between flag.set(false) and processingFlags.remove(chatId, flag)
          in the finally block, a new message creates a fresh AtomicBoolean. The remove()
          uses reference equality, removes the OLD flag, orphans the NEW one permanently at true.
          Result: all future messages from that chatId get "still processing" forever.

Fix: Caffeine Cache<String, AtomicBoolean> with expireAfterAccess.

NOTE: After T67-T1, TelegramAgentHandler lives in arcclaw-channel-telegram.
Apply this change to that module.

CAFFEINE IS ALREADY A DEPENDENCY — do not add new dependencies.

=============================================================
CHANGE: TelegramAgentHandler.java
=============================================================

1. Add import:
   import com.github.benmanes.caffeine.cache.Cache;
   import com.github.benmanes.caffeine.cache.Caffeine;
   import java.time.Duration;

2. Replace field declaration:

BEFORE:
   private final ConcurrentHashMap<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();

AFTER:
   private static final Duration FLAG_TTL = Duration.ofMinutes(10);
   // 10 min >> any realistic agent loop. Caffeine expiry prevents both the memory leak
   // and the reference-identity race in the remove() call pattern.
   private final Cache<String, AtomicBoolean> processingFlags = Caffeine.newBuilder()
       .expireAfterAccess(FLAG_TTL)
       .build();

3. Replace computeIfAbsent with Cache.get():

BEFORE:
   AtomicBoolean flag = processingFlags.computeIfAbsent(chatId, id -> new AtomicBoolean(false));

AFTER:
   AtomicBoolean flag = processingFlags.get(chatId, id -> new AtomicBoolean(false));

4. Fix the finally block:

BEFORE:
   } finally {
       flag.set(false);
       processingFlags.remove(chatId, flag);  // ← reference-identity race bug
   }

AFTER:
   } finally {
       flag.set(false);
       // DO NOT call processingFlags.invalidate(chatId) here.
       // Caffeine.get() returns the SAME AtomicBoolean on cache hit.
       // flag.set(false) resets the same object; next message's get() returns it as false.
       // Caffeine TTL handles eviction. Manual invalidate would orphan a concurrent message's flag.
   }

5. Add package-private accessor for testability:

   /** For tests only — checks if a chat is currently being processed. */
   boolean isProcessing(String chatId) {
       AtomicBoolean flag = processingFlags.getIfPresent(chatId);
       return flag != null && flag.get();
   }

=============================================================
TESTS: TelegramAgentHandlerTest.java
=============================================================

Add these tests (use CountDownLatch to coordinate async pipeline):

@Test
void rapidConcurrentMessagesFromSameChatAreSerialised() throws Exception {
    String chatId = "race-chat-" + UUID.randomUUID();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch firstComplete = new CountDownLatch(1);
    // Configure mock pipeline: first call signals firstStarted, then awaits firstComplete
    // Second message sent while first is running → must get "still processing" reply
    // After firstComplete is counted down → third message must be accepted normally
    assertThat(handler.isProcessing(chatId)).isFalse(); // clean state after first completes
}

@Test
void processingFlagIsFalseAfterSuccessfulProcessing() {
    String chatId = "chat-ok-" + UUID.randomUUID();
    // Process one message synchronously (no async)
    // Verify isProcessing(chatId) == false after completion
    assertThat(handler.isProcessing(chatId)).isFalse();
}

@Test
void differentChatIdsHaveIndependentFlags() {
    String chatA = "chat-a-" + UUID.randomUUID();
    String chatB = "chat-b-" + UUID.randomUUID();
    // Lock chatA manually by setting flag = true
    // Verify chatB returns HandleResult.CONTINUE (not blocked)
    assertThat(handler.isProcessing(chatB)).isFalse();
}

=============================================================
VERIFICATION
=============================================================

mvn clean test -pl arcclaw-channel-telegram -Dtest=TelegramAgentHandlerTest

SUCCESS CRITERIA
- processingFlags field type is Cache<String, AtomicBoolean>
- No ConcurrentHashMap import for processingFlags
- No remove(chatId, flag) call in finally block
- rapidConcurrentMessages test passes without deadlock
- All tests pass
```

***
## PROMPT T67-5: Remove `ChannelSender` from `MessageContext`
```
You are implementing Phase 67-T5 for ArcClaw — Quarkus 3.27.2 / JDK 25.
Role: Senior Java Architect.

GOAL
MessageContext currently carries a ChannelSender field — transport concerns embedded in
orchestration. ChannelPluginRegistry (P66-T5) is the correct resolution point.
Remove ChannelSender from MessageContext; resolve at reply time via ChannelPluginRegistry.

MANDATORY ARCHITECTURE NUANCES
- Nuance #38: WorkspaceContext.*() for tools; WorkspaceScopeHolder.*() for reflexes.
  Handlers are orchestration layer — they use MessageContext, NOT WorkspaceContext directly.
- Nuance #11: MessageContext is passed through the entire handler chain. Any field removal
  must be propagated to ALL constructors and ALL callers — use rg to find them all.
- After T67-T1, TelegramAgentHandler lives in arcclaw-channel-telegram. Its direct
  @Inject TelegramChannelSender is acceptable — it IS a Telegram class in a Telegram module.

=============================================================
STEP 1: Audit all getSender() call sites
=============================================================

rg "getSender()\|\.sender()\|context\.getSender" --type java -n

Expected callsites:
  - AgentDispatchHandler.handle()       — sends final agent reply
  - CommandDispatchHandler.handle()     — sends reflex reply
  - TelegramAgentHandler (error path)   — sends fallback error message (keep direct injection)
  - Various Handler test files

=============================================================
STEP 2: Modify MessageContext.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/pipeline/MessageContext.java

REMOVE:
  private final ChannelSender sender;
  public ChannelSender getSender() { return sender; }

  Also remove ChannelSender from:
  - Constructor parameter(s)
  - Builder if present
  - Any copy/with methods

ADD (convenience accessor — already computable from user field):
  public ChannelType getChannelType() {
      return user != null ? user.channelType() : ChannelType.TELEGRAM;
  }

=============================================================
STEP 3: Update AgentDispatchHandler.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/AgentDispatchHandler.java

ADD injection:
  @Inject ChannelPluginRegistry channelPluginRegistry;

REPLACE sender usage:

BEFORE:
  context.getSender().send(context.getUser(), replyNodes);

AFTER:
  channelPluginRegistry.find(context.getChannelType())
      .map(ChannelPlugin::sender)
      .ifPresentOrElse(
          s -> s.send(context.getUser(), replyNodes),
          () -> log.warnf("AgentDispatchHandler: no ChannelPlugin for type %s — reply dropped",
              context.getChannelType()));

For sendRaw calls — same pattern but with sender.sendRaw(context.getUser(), text).

=============================================================
STEP 4: Update CommandDispatchHandler.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/pipeline/handlers/CommandDispatchHandler.java

Same pattern as AgentDispatchHandler — inject ChannelPluginRegistry, resolve sender at
each send point.

BEFORE:
  context.getSender().sendRaw(context.getUser(), response);

AFTER:
  channelPluginRegistry.find(context.getChannelType())
      .map(ChannelPlugin::sender)
      .ifPresentOrElse(
          s -> s.sendRaw(context.getUser(), response),
          () -> log.warnf("CommandDispatchHandler: no sender for channelType %s", context.getChannelType()));

=============================================================
STEP 5: Update TelegramAgentHandler (post-T1 lives in arcclaw-channel-telegram)
=============================================================

TelegramAgentHandler is a Telegram-specific class. It already injects TelegramChannelSender
directly. The error fallback path:

BEFORE:
  context.getSender().sendRaw(context.getUser(), "Sorry, an internal error occurred.");

AFTER (keep direct injection, no registry needed inside the Telegram module):
  sender.sendRaw(context.getUser(), "Sorry, an internal error occurred.");
  // where @Inject TelegramChannelSender sender is already present

=============================================================
STEP 6: Update MessageContext construction call sites
=============================================================

rg "new MessageContext" --type java -n

For each call: remove the ChannelSender argument from the constructor call.

BEFORE:
  MessageContext context = new MessageContext(chatId, user, channelMessage, sender);

AFTER:
  MessageContext context = new MessageContext(chatId, user, channelMessage);

=============================================================
STEP 7: Update ALL test files that construct MessageContext
=============================================================

rg "new MessageContext" arcclaw-core/src/test --type java -n
rg "new MessageContext" arcclaw-app/src/test --type java -n
rg "new MessageContext" arcclaw-channel-telegram/src/test --type java -n

For each: remove the ChannelSender argument. Tests that were passing a mock ChannelSender
to MessageContext should instead mock ChannelPluginRegistry.find() to return a ChannelPlugin
whose .sender() returns their mock ChannelSender.

=============================================================
STEP 8: Tests
=============================================================

File: arcclaw-core/src/test/java/com/arcclaw/core/pipeline/MessageContextTest.java — add:

@Test
void messageContextHasNoGetSenderMethod() {
    assertThatThrownBy(() -> MessageContext.class.getMethod("getSender"))
        .isInstanceOf(NoSuchMethodException.class)
        .describedAs("getSender() was removed in P67-T5. Use ChannelPluginRegistry.");
}

@Test
void getChannelTypeDerivesFromUser() {
    ChannelUser user = new ChannelUser("user1", "User", ChannelType.TELEGRAM, "chat1");
    MessageContext ctx = new MessageContext("chat1", user, null);
    assertThat(ctx.getChannelType()).isEqualTo(ChannelType.TELEGRAM);
}

File: arcclaw-core/src/test/java/com/arcclaw/core/pipeline/handlers/AgentDispatchHandlerTest.java — add:

@Test
void agentDispatchHandlerResolvesChannelSenderFromPluginRegistry() {
    // Mock ChannelPluginRegistry.find(TELEGRAM) → mock ChannelPlugin → mock ChannelSender
    // Verify: captor on sender.send() — registry-resolved sender was called, not hardcoded
    ArgumentCaptor<List<ReplyNode>> captor = ArgumentCaptor.forClass(List.class);
    verify(mockSender).send(any(ChannelUser.class), captor.capture());
    assertThat(captor.getValue()).isNotEmpty();
}

=============================================================
VERIFICATION
=============================================================

rg "getSender()" arcclaw-core/src/main/java --type java   # must return 0
rg "ChannelSender sender" com/arcclaw/core/pipeline/MessageContext.java  # must return 0
mvn clean test -pl arcclaw-core,arcclaw-app,arcclaw-channel-telegram

SUCCESS CRITERIA
- MessageContext has no getSender() method
- MessageContext constructor has no ChannelSender parameter
- AgentDispatchHandler resolves sender via ChannelPluginRegistry
- CommandDispatchHandler resolves sender via ChannelPluginRegistry
- All 810+ tests pass (test files updated to 3-arg constructor)
```

***
## PROMPT T67-6: SandboxExecTool — Last-Mile Wire
```
You are implementing Phase 67-T6 for ArcClaw — Quarkus 3.27.2 / JDK 25.
Role: Senior Java Developer.

GOAL
The sandbox infrastructure is 100% complete and tested in arcclaw-core/src/main/java/com/arcclaw/core/sandbox/:
  ProcessIsolationExecutor, PodmanContainerExecutor, NamespaceExecutor, DirectExecutor,
  SandboxCircuitBreaker, SandboxSecretResolver, TmpfsSecretTransport, ToolExecutionException.

ToolCapability.SANDBOX_EXEC exists but ZERO AgentTool beans implement it.
This is the only missing piece. Wire it up.

NEW NUANCE #39 (document in @Architecture_Invariants.md):
  SandboxExecTool MUST NEVER propagate exceptions to ToolInvoker.
  ProcessIsolationExecutor failures (ToolExecutionException, SecurityException, IOException,
  TimeoutException) MUST be caught and returned as ToolResponse.failure(message).
  BudgetExceededException is the only exception allowed to propagate — AgentStateMachineRunner
  handles it. Output > 8192 chars MUST be truncated at exactly 8192 chars with the suffix
  "[output truncated at 8192 chars]" appended. Never include raw Java stack traces in tool output.

=============================================================
STEP 1: Create SandboxExecTool.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/tools/SandboxExecTool.java

@ApplicationScoped
public class SandboxExecTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SandboxExecTool.class);
    private static final int MAX_OUTPUT_CHARS = 8192;
    private static final String TRUNCATION_SUFFIX = "[output truncated at 8192 chars]";
    private static final Set<String> ALLOWED_LANGUAGES =
        Set.of("python", "python3", "bash", "node");

    @Inject ProcessIsolationExecutor isolationExecutor;

    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = ".data/workspaces")
    String workspaceRootDir;

    @Override
    public ToolCapability capability() { return ToolCapability.SANDBOX_EXEC; }

    @Override
    public ToolResponse execute(String paramsJson) {
        // Parse paramsJson with Jackson ObjectMapper (inject @Inject ObjectMapper om):
        //   { "code": "print('hello')", "language": "python",
        //     "args": ["--verbose"], "secrets": {"MY_TOKEN": "vault-key-name"} }
        //
        // VALIDATE language — if not in ALLOWED_LANGUAGES:
        //   return ToolResponse.failure("Unsupported language: " + lang +
        //       ". Allowed: " + ALLOWED_LANGUAGES);
        //
        // BUILD COMMAND LIST:
        //   python / python3 → List.of("python3", "-c", code)
        //   bash             → List.of("bash", "-c", code)
        //   node             → List.of("node", "-e", code)
        //   Then appendAll elements from "args" array if present
        //
        // RESOLVE WORKSPACE:
        //   String sessionId = WorkspaceContext.requireSessionId();
        //   Path workspaceRoot = Path.of(workspaceRootDir, sessionId);
        //
        // RESOLVE SECRETS:
        //   Map<String, String> requiredSecrets = paramsJson.secrets ?? Map.of()
        //   (key = env var name, value = vault key name)
        //
        // EXECUTE:
        //   String output = isolationExecutor.executeSandboxed(
        //       sessionId, workspaceRoot, command, requiredSecrets);
        //
        // TRUNCATE:
        //   if (output.length() > MAX_OUTPUT_CHARS) {
        //       output = output.substring(0, MAX_OUTPUT_CHARS) + TRUNCATION_SUFFIX;
        //   }
        //
        // RETURN: ToolResponse.success(output)
        //
        // CATCH ToolExecutionException e:
        //   return ToolResponse.failure("Execution failed (exit " + e.exitCode() + "): " + e.getMessage());
        //
        // CATCH TimeoutException | InterruptedException:
        //   return ToolResponse.failure("Sandbox execution timed out.");
        //
        // CATCH SecurityException e:
        //   return ToolResponse.failure("Sandbox security violation: " + e.getMessage());
        //
        // CATCH BudgetExceededException: RETHROW — do NOT catch.
        //
        // CATCH JsonProcessingException e:
        //   return ToolResponse.failure("Invalid parameters: " + e.getMessage());
        //
        // CATCH Exception e (catch-all, last resort):
        //   log.errorf(e, "SandboxExecTool unexpected error for session %s", WorkspaceContext.requireSessionId());
        //   return ToolResponse.failure("Sandbox error: " + e.getMessage());
    }
}

=============================================================
STEP 2: Update ToolRegistry.java
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolRegistry.java

Inject: @Inject SandboxExecTool sandboxExecTool;

In the registry initializer (follow exact pattern of GithubTool, MemorySearchTool, etc.):
  registry.put(sandboxExecTool.capability().toolName(), sandboxExecTool);

=============================================================
STEP 3: Update AgentRoleEnum.java — CODER + DEBUGGER get SANDBOX_EXEC
=============================================================

File: arcclaw-core/src/main/java/com/arcclaw/core/agent/role/AgentRoleEnum.java

For CODER's capabilities() Set: add ToolCapability.SANDBOX_EXEC
For DEBUGGER's capabilities() Set: add ToolCapability.SANDBOX_EXEC

COORDINATOR, RESEARCHER, ARCHITECT, SYNTHESIZER must NOT get SANDBOX_EXEC.

=============================================================
STEP 4: Update ReflectionConfig.java
=============================================================

Add SandboxExecTool.class to @RegisterForReflection targets.

=============================================================
STEP 5: Update @Architecture_Invariants.md
=============================================================

Append Nuance #39 as defined in the NEW NUANCE section above.

=============================================================
STEP 6: Update application.yaml sandbox config (merge, don't duplicate)
=============================================================

Under arcclaw:
  sandbox:
    enabled: true
    strategy: PODMAN
    podman-path: podman
    container-image: arcclaw-tool-runner:latest
    timeout-seconds: 30
    memory-limit: 512m
    cpu-limit: "0.5"
    tmpfs-path: /run/arcclaw/secrets

%dev:
  arcclaw:
    sandbox:
      strategy: NONE      # no Podman needed in dev

%test:
  arcclaw:
    sandbox:
      strategy: NONE      # tests use DirectExecutor

=============================================================
STEP 7: Create SandboxExecToolTest.java
=============================================================

File: arcclaw-core/src/test/java/com/arcclaw/core/tools/SandboxExecToolTest.java

ALL tests wrapped in: WorkspaceContext.run("test-session","user1","chat1", AutonomyLevel.FULL, "coder", () -> {})

@Mock ProcessIsolationExecutor mockIsolationExecutor;

@Test void python_language_builds_correct_command() {
    // Verify isolationExecutor called with ["python3", "-c", de>]
}

@Test void bash_language_builds_correct_command() {
    // Verify ["bash", "-c", de>]
}

@Test void node_language_builds_correct_command() {
    // Verify ["node", "-e", de>]
}

@Test void unknown_language_returns_failure_without_calling_executor() {
    ToolResponse r = tool.execute("{\"code\":\"x\",\"language\":\"ruby\"}");
    assertThat(r.isSuccess()).isFalse();
    assertThat(r.content()).contains("Unsupported language");
    verifyNoInteractions(mockIsolationExecutor);
}

@Test void output_over_8192_chars_is_truncated() {
    String bigOutput = "A".repeat(10_000);
    when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
        .thenReturn(bigOutput);
    ToolResponse r = tool.execute("{\"code\":\"x\",\"language\":\"python\"}");
    assertThat(r.content()).hasSize(8192 + TRUNCATION_SUFFIX.length());
    assertThat(r.content()).endsWith("[output truncated at 8192 chars]");
}

@Test void tool_execution_exception_returns_failure_not_thrown() {
    when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
        .thenThrow(new ToolExecutionException("exit 1", 1));
    ToolResponse r = tool.execute("{\"code\":\"exit 1\",\"language\":\"bash\"}");
    assertThat(r.isSuccess()).isFalse();
    assertThat(r.content()).contains("exit 1");
}

@Test void timeout_exception_returns_failure_not_thrown() {
    when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
        .thenThrow(new TimeoutException("30s exceeded"));
    ToolResponse r = tool.execute("{\"code\":\"sleep 999\",\"language\":\"bash\"}");
    assertThat(r.isSuccess()).isFalse();
    assertThat(r.content()).contains("timed out");
}

@Test void budget_exceeded_exception_propagates() {
    when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
        .thenThrow(new BudgetExceededException("over limit"));
    assertThatThrownBy(() -> tool.execute("{\"code\":\"x\",\"language\":\"python\"}"))
        .isInstanceOf(BudgetExceededException.class);
}

@Test void capability_returns_sandbox_exec() {
    assertThat(tool.capability()).isEqualTo(ToolCapability.SANDBOX_EXEC);
}

@Test void empty_secrets_map_passed_when_paramsJson_has_no_secrets_field() {
    // Capture the requiredSecrets argument
    ArgumentCaptor<Map> secretsCaptor = ArgumentCaptor.forClass(Map.class);
    tool.execute("{\"code\":\"x\",\"language\":\"python\"}");
    verify(mockIsolationExecutor).executeSandboxed(any(), any(), any(), secretsCaptor.capture());
    assertThat(secretsCaptor.getValue()).isEmpty();
}

@Test void coder_role_has_sandbox_exec_capability() {
    assertThat(AgentRoleEnum.CODER.capabilities()).contains(ToolCapability.SANDBOX_EXEC);
}

@Test void debugger_role_has_sandbox_exec_capability() {
    assertThat(AgentRoleEnum.DEBUGGER.capabilities()).contains(ToolCapability.SANDBOX_EXEC);
}

@Test void coordinator_does_not_have_sandbox_exec_capability() {
    assertThat(AgentRoleEnum.COORDINATOR.capabilities())
        .doesNotContain(ToolCapability.SANDBOX_EXEC);
}

=============================================================
VERIFICATION
=============================================================

mvn clean test -pl arcclaw-core -Dtest=SandboxExecToolTest,ToolRegistryTest
mvn clean test   # full suite

SUCCESS CRITERIA
- SandboxExecTool.capability().toolName() equals "sandboxexec"
- CODER and DEBUGGER roles include SANDBOX_EXEC in capabilities()
- Nuance #39 appended to @Architecture_Invariants.md
- 10 new SandboxExecTool tests all pass
- All 810+ tests remain green
```

***
## P67 Execution Order & Success Criteria
```
Day 1–2: T67-1 — arcclaw-channel-telegram module extraction
          Verify: rg "com.arcclaw.app.channel.telegram" arcclaw-app/src/main/java → 0 results

Day 3:   T67-3 — ChannelSenderRegistry retirement + ApprovalTimeout bias fix
          Verify: rg "ChannelSenderRegistry" --type java → 0 results
                  rg "orElse(ChannelType.TELEGRAM)" --type java → 0 results

Day 4:   T67-2 — PromptTemplateEngine templateFor() warning
          Verify: mvn test -pl arcclaw-core -Dtest=SystemPromptBuilderTest → green

Day 5am: T67-4 — processingFlags → Caffeine TTL
          Verify: TelegramAgentHandlerTest.rapidConcurrentMessages → green

Day 5pm: T67-5 — MessageContext ChannelSender removal
          Verify: rg "getSender()" arcclaw-core/src/main/java → 0 results

Day 6:   T67-6 — SandboxExecTool
          Verify: ToolCapability.SANDBOX_EXEC has a registered AgentTool
                  CODER + DEBUGGER capabilities include SANDBOX_EXEC

Day 7:   mvn clean test → all 810+ tests pass
         Manual Telegram test: send/receive, /approve, /crons, sandbox trigger
         rg "com.arcclaw.app.channel.telegram" arcclaw-app/src/main/java → 0
         rg "ChannelSenderRegistry" --type java → 0
         rg "getSender()" arcclaw-core/src/main/java → 0

FINAL P67 SCORE: 9.2 → 9.7/10
```

**Post-P67 backlog for P68:** `ToolInvocationStrategy` SPI (Grok was right on substance, wrong on timing) — splits `ToolInvoker`'s three responsibilities into `InternalAgentToolStrategy` + `ExternalMcpToolStrategy` + pre/post hooks, making dry-run and mock-for-test trivial. 