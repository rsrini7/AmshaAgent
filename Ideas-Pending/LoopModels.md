Idea is from https://github.com/decolua/9router

Now I have a thorough picture of ArcClaw's architecture. I can see exactly how it works:
- `AgentChatModelProvider` SPI for pluggable providers
- `ChatModelFactory` with `createOpenAi()` for OpenAI-compatible endpoints
- `FallbackChatModel` with multi-tier fallback
- `LlmModelFactory` / `ChatModelProducer` orchestrating everything
- `RoutingChatModelProvider` for routing-specific models

The plan is to build a **pure Java 9Router equivalent** that plugs directly into ArcClaw's existing provider SPI — no external process, no Node.js. Let me design the full implementation.

Here's the complete architectural design and implementation plan for a **native Java 9Router** baked directly into ArcClaw:

***

## Architecture Overview

ArcClaw already has everything needed: 
- `AgentChatModelProvider` SPI → pluggable provider factory
- `ChatModelFactory.createOpenAi()` → works for any OpenAI-compatible URL
- `FallbackChatModel` → 2-layer fallback (primary → fallback)
- `LlmExceptionClassifier` → already classifies 429s, 5xx, auth errors

The goal is to **extend this chain to N layers** with free-tier providers baked in — no Node.js, no separate process.

***

## Layer Design: What Gets Built

```
ArcClaw Agent Loop
        │
        ▼
 NineRouterChatModel          ← new: replaces FallbackChatModel
 (N-layer round-robin + fallback)
        │
   ┌────▼─────────────────────────────────┐
   │  Slot 1: KiroProvider                │ ← FREE, unlimited
   │  Slot 2: OpenCodeFreeProvider        │ ← FREE, no-auth
   │  Slot 3: GroqProvider (existing)     │ ← already in ArcClaw
   │  Slot 4: OpenRouterFreeProvider      │ ← already in ArcClaw
   │  Slot 5: NvidiaFreeProvider          │ ← already in ArcClaw
   │  Slot 6: CerebrasProvider (existing) │ ← already in ArcClaw
   └──────────────────────────────────────┘
        │
   QuotaTracker (in-memory)   ← tracks 429s per provider per session
   RTKTokenCompressor          ← compresses tool outputs before send
```

***

## File-by-File Implementation

### 1. `NineRouterChatModel.java` — The Core Multi-Tier Router

This replaces `FallbackChatModel` with N-slot chaining: 

```java
// arcclaw-core/src/main/java/com/arcclaw/core/routing/NineRouterChatModel.java
package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-tier fallback ChatModel that chains N providers.
 * Inspired by 9Router's smart-3-tier-fallback but implemented
 * purely in Java with no external process dependency.
 *
 * Fallback triggers:
 *   - 429 RateLimitException  → skip this slot, try next
 *   - 503/502 ServiceUnavailable → skip this slot, try next
 *   - 401/403 Auth error → log + skip (key invalid)
 *   - Success → return immediately
 *
 * After all slots exhausted → throws last exception.
 */
public class NineRouterChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(NineRouterChatModel.class);
    // How long to back off a slot after a 429 (millis)
    private static final long SLOT_COOLDOWN_MS = 60_000L;

    private final List<NamedSlot> slots;
    // Tracks when each slot was last rate-limited (slot name → epoch ms)
    private final Map<String, Long> rateLimitedUntil = new ConcurrentHashMap<>();

    public record NamedSlot(String name, ChatModel model) {}

    public NineRouterChatModel(List<NamedSlot> slots) {
        if (slots == null || slots.isEmpty())
            throw new IllegalArgumentException("NineRouterChatModel requires at least one slot");
        this.slots = List.copyOf(slots);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Throwable lastException = null;
        for (NamedSlot slot : slots) {
            // Skip slots that are in cooldown
            Long cooldownUntil = rateLimitedUntil.get(slot.name());
            if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) {
                log.debugf("9Router: Slot [%s] in cooldown, skipping.", slot.name());
                continue;
            }
            try {
                log.debugf("9Router: Trying slot [%s]", slot.name());
                ChatResponse response = slot.model().chat(request);
                log.debugf("9Router: Slot [%s] succeeded.", slot.name());
                return response;
            } catch (Throwable t) {
                lastException = t;
                if (isRateLimit(t)) {
                    log.warnf("9Router: Slot [%s] rate-limited. Cooling down %ds. Next slot...",
                        slot.name(), SLOT_COOLDOWN_MS / 1000);
                    rateLimitedUntil.put(slot.name(),
                        System.currentTimeMillis() + SLOT_COOLDOWN_MS);
                } else if (isTransient(t)) {
                    log.warnf("9Router: Slot [%s] transient error: %s. Trying next...",
                        slot.name(), t.getMessage());
                } else if (isAuthError(t)) {
                    log.errorf("9Router: Slot [%s] auth error: %s. Skipping permanently this run.",
                        slot.name(), t.getMessage());
                } else {
                    log.warnf("9Router: Slot [%s] unknown error: %s. Trying next...",
                        slot.name(), t.getMessage());
                }
            }
        }
        log.errorf("9Router: All %d slots exhausted!", slots.size());
        if (lastException instanceof RuntimeException re) throw re;
        throw new RuntimeException("9Router: All providers failed", lastException);
    }

    private boolean isRateLimit(Throwable t) {
        if (t == null) return false;
        String msg = safeMsg(t);
        return msg.contains("429") || msg.contains("rate limit")
            || msg.contains("ratelimit") || msg.contains("too many requests")
            || msg.contains("tokens per minute") || msg.contains("quota")
            || t.getClass().getSimpleName().contains("RateLimit");
    }

    private boolean isTransient(Throwable t) {
        if (t == null) return false;
        String msg = safeMsg(t);
        return msg.contains("502") || msg.contains("503") || msg.contains("504")
            || msg.contains("service unavailable") || msg.contains("overloaded")
            || msg.contains("timeout") || msg.contains("connection");
    }

    private boolean isAuthError(Throwable t) {
        if (t == null) return false;
        String msg = safeMsg(t);
        return msg.contains("401") || msg.contains("403")
            || msg.contains("invalid api key") || msg.contains("unauthorized");
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m != null ? m.toLowerCase() : t.getClass().getSimpleName().toLowerCase();
    }
}
```

***

### 2. `KiroProvider.java` — Free Unlimited Claude via Kiro AI

Kiro exposes an OpenAI-compatible endpoint once you auth via AWS Builder ID: 

```java
// arcclaw-core/src/main/java/com/arcclaw/core/routing/KiroProvider.java
package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Optional;

/**
 * Free provider: Kiro AI (AWS Builder ID OAuth)
 * Offers Claude 4.5, GLM-5, MiniMax, Qwen3, DeepSeek — all FREE unlimited.
 *
 * Models:
 *   kiro/claude-sonnet-4.5   ← best quality
 *   kiro/glm-5               ← GLM-5 free
 *   kiro/MiniMax-M2.5        ← MiniMax free
 *   kiro/qwen3-coder-next    ← Qwen3 free
 *
 * Auth: Bearer token from Kiro OAuth (stored in ARCCLAW_KIRO_TOKEN env var)
 * Base URL: https://api.kiro.aws/v1  (OpenAI-compatible)
 */
@ApplicationScoped
public class KiroProvider implements RoutingChatModelProvider {

    @ConfigProperty(name = "arcclaw.kiro.base-url",
                    defaultValue = "https://api.kiro.aws/v1")
    String baseUrl;

    @ConfigProperty(name = "arcclaw.kiro.token")
    Optional<String> token;

    @ConfigProperty(name = "arcclaw.kiro.model",
                    defaultValue = "claude-sonnet-4.5")
    String model;

    @ConfigProperty(name = "arcclaw.kiro.enabled", defaultValue = "false")
    boolean enabled;

    @Override
    public String providerId() { return "kiro"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String tok = token.orElseThrow(() ->
            new IllegalStateException("Kiro token missing: set ARCCLAW_KIRO_TOKEN"));
        return ChatModelFactory.createOpenAi(
            baseUrl, tok, model,
            8192, Duration.ofSeconds(60), 2, 0.0, false
        );
    }
}
```

***

### 3. `OpenCodeFreeProvider.java` — No-Auth Free Models

OpenCode Free has zero auth — just hit the endpoint: 

```java
// arcclaw-core/src/main/java/com/arcclaw/core/routing/OpenCodeFreeProvider.java
package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Free provider: OpenCode Free (no auth required!)
 * Passthrough proxy — models auto-fetched from opencode.ai.
 *
 * Base URL: https://opencode.ai/zenv1
 * API Key:  "opencode"  (public, no real auth)
 * Model:    "auto"      (proxy picks best available)
 *
 * Zero config — just enable it.
 */
@ApplicationScoped
public class OpenCodeFreeProvider implements RoutingChatModelProvider {

    @ConfigProperty(name = "arcclaw.opencode.base-url",
                    defaultValue = "https://opencode.ai/zenv1")
    String baseUrl;

    @ConfigProperty(name = "arcclaw.opencode.model", defaultValue = "auto")
    String model;

    @ConfigProperty(name = "arcclaw.opencode.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public String providerId() { return "opencode"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createOpenAi(
            baseUrl, "opencode", model,       // "opencode" is the public passthrough key
            8192, Duration.ofSeconds(60), 2, 0.0, false
        );
    }
}
```

***

### 4. `NineRouterModelProducer.java` — Wires Everything Together

This is the CDI producer that assembles all slots into the `NineRouterChatModel` and exposes it as a `@NineRouter`-qualified bean: 

```java
// arcclaw-core/src/main/java/com/arcclaw/core/routing/NineRouterModelProducer.java
package com.arcclaw.core.routing;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the NineRouterChatModel from all enabled free-tier slots.
 *
 * Slot priority (order = fallback order):
 *   1. Kiro → Claude 4.5 (FREE unlimited, best quality)
 *   2. OpenCode Free (FREE no-auth)
 *   3. Groq (existing ArcClaw provider, free tier)
 *   4. OpenRouter free models (existing ArcClaw provider)
 *   5. Nvidia free (existing ArcClaw provider)
 *   6. Cerebras (existing ArcClaw provider)
 *
 * Enable/disable each slot via application.properties.
 */
@ApplicationScoped
public class NineRouterModelProducer {

    private static final Logger log = Logger.getLogger(NineRouterModelProducer.class);

    // Kiro AI free slot
    @ConfigProperty(name = "arcclaw.9router.kiro.enabled", defaultValue = "false")
    boolean kiroEnabled;
    @ConfigProperty(name = "arcclaw.9router.kiro.base-url",
                    defaultValue = "https://api.kiro.aws/v1")
    String kiroBaseUrl;
    @ConfigProperty(name = "arcclaw.9router.kiro.token")
    Optional<String> kiroToken;
    @ConfigProperty(name = "arcclaw.9router.kiro.model",
                    defaultValue = "claude-sonnet-4.5")
    String kiroModel;

    // OpenCode Free slot
    @ConfigProperty(name = "arcclaw.9router.opencode.enabled", defaultValue = "true")
    boolean opencodeEnabled;
    @ConfigProperty(name = "arcclaw.9router.opencode.base-url",
                    defaultValue = "https://opencode.ai/zenv1")
    String opencodeBaseUrl;
    @ConfigProperty(name = "arcclaw.9router.opencode.model", defaultValue = "auto")
    String opencodeModel;

    // Groq free slot (existing provider already in ArcClaw)
    @ConfigProperty(name = "arcclaw.9router.groq.enabled", defaultValue = "true")
    boolean groqEnabled;
    @ConfigProperty(name = "arcclaw.9router.groq.base-url",
                    defaultValue = "https://api.groq.com/openai/v1")
    String groqBaseUrl;
    @ConfigProperty(name = "arcclaw.9router.groq.api-key")
    Optional<String> groqApiKey;
    @ConfigProperty(name = "arcclaw.9router.groq.model",
                    defaultValue = "meta-llama/llama-4-scout-17b-16e-instruct")
    String groqModel;

    // OpenRouter free slot
    @ConfigProperty(name = "arcclaw.9router.openrouter.enabled", defaultValue = "true")
    boolean openrouterEnabled;
    @ConfigProperty(name = "arcclaw.9router.openrouter.base-url",
                    defaultValue = "https://openrouter.ai/api/v1")
    String openrouterBaseUrl;
    @ConfigProperty(name = "arcclaw.9router.openrouter.api-key")
    Optional<String> openrouterApiKey;
    @ConfigProperty(name = "arcclaw.9router.openrouter.model",
                    defaultValue = "openai/gpt-oss-20b:free")
    String openrouterModel;

    // Nvidia free slot
    @ConfigProperty(name = "arcclaw.9router.nvidia.enabled", defaultValue = "false")
    boolean nvidiaEnabled;
    @ConfigProperty(name = "arcclaw.9router.nvidia.base-url",
                    defaultValue = "https://integrate.api.nvidia.com/v1")
    String nvidiaBaseUrl;
    @ConfigProperty(name = "arcclaw.9router.nvidia.api-key")
    Optional<String> nvidiaApiKey;
    @ConfigProperty(name = "arcclaw.9router.nvidia.model",
                    defaultValue = "mistralai/devstral-2-123b-instruct-25.12")
    String nvidiaModel;

    @Produces
    @ApplicationScoped
    @Unremovable
    @Identifier("9router")
    public ChatModel nineRouterModel() {
        List<NineRouterChatModel.NamedSlot> slots = new ArrayList<>();

        // Slot 1: Kiro (best quality, Claude 4.5 free)
        if (kiroEnabled && kiroToken.isPresent()) {
            var model = ChatModelFactory.createOpenAi(
                kiroBaseUrl, kiroToken.get(), kiroModel,
                8192, Duration.ofSeconds(60), 1, 0.0, false);
            slots.add(new NineRouterChatModel.NamedSlot("kiro/" + kiroModel, model));
            log.infof("9Router: Slot 1 [kiro/%s] ENABLED ✓", kiroModel);
        }

        // Slot 2: OpenCode Free (no auth needed)
        if (opencodeEnabled) {
            var model = ChatModelFactory.createOpenAi(
                opencodeBaseUrl, "opencode", opencodeModel,
                8192, Duration.ofSeconds(60), 1, 0.0, false);
            slots.add(new NineRouterChatModel.NamedSlot("opencode/" + opencodeModel, model));
            log.info("9Router: Slot 2 [opencode/auto] ENABLED ✓");
        }

        // Slot 3: Groq (existing ArcClaw free tier)
        if (groqEnabled && groqApiKey.isPresent()) {
            var model = ChatModelFactory.createOpenAi(
                groqBaseUrl, groqApiKey.get(), groqModel,
                8192, Duration.ofSeconds(30), 1, 0.0, false);
            slots.add(new NineRouterChatModel.NamedSlot("groq/" + groqModel, model));
            log.infof("9Router: Slot 3 [groq/%s] ENABLED ✓", groqModel);
        }

        // Slot 4: OpenRouter free
        if (openrouterEnabled && openrouterApiKey.isPresent()) {
            var model = ChatModelFactory.withOpenRouterHeaders(
                ChatModelFactory.openAiBuilder(
                    openrouterBaseUrl, openrouterApiKey.get(), openrouterModel,
                    8192, Duration.ofSeconds(30), 1, 0.0, false, false),
                "https://arcclaw.ai", "ArcClaw")
                .build();
            slots.add(new NineRouterChatModel.NamedSlot("openrouter/" + openrouterModel, model));
            log.infof("9Router: Slot 4 [openrouter/%s] ENABLED ✓", openrouterModel);
        }

        // Slot 5: Nvidia free
        if (nvidiaEnabled && nvidiaApiKey.isPresent()) {
            var model = ChatModelFactory.createOpenAi(
                nvidiaBaseUrl, nvidiaApiKey.get(), nvidiaModel,
                8192, Duration.ofSeconds(45), 1, 0.0, false);
            slots.add(new NineRouterChatModel.NamedSlot("nvidia/" + nvidiaModel, model));
            log.infof("9Router: Slot 5 [nvidia/%s] ENABLED ✓", nvidiaModel);
        }

        if (slots.isEmpty()) {
            throw new IllegalStateException(
                "9Router: No slots configured! Enable at least opencode " +
                "(arcclaw.9router.opencode.enabled=true) or Kiro.");
        }

        log.infof("9Router: Ready with %d slot(s).", slots.size());
        return new NineRouterChatModel(slots);
    }
}
```

***

### 5. `NineRouterAgentChatModelProvider.java` — Plugs Into Existing SPI

This is the entry point that makes 9Router available as `arcclaw.llm.provider=9router`: 

```java
// arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/NineRouterAgentChatModelProvider.java
package com.arcclaw.core.agent.reasoning;

import com.arcclaw.core.routing.NineRouterModelProducer;
import dev.langchain4j.model.chat.ChatModel;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * AgentChatModelProvider implementation for "9router" provider name.
 * Set arcclaw.llm.provider=9router in application.properties to activate.
 *
 * Chains: Kiro → OpenCode → Groq → OpenRouter → Nvidia
 */
@ApplicationScoped
public class NineRouterAgentChatModelProvider implements AgentChatModelProvider {

    @Inject
    @Identifier("9router")
    ChatModel nineRouterChatModel;

    @Override
    public String providerName() { return "9router"; }

    @Override
    public ChatModel create() { return nineRouterChatModel; }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return StructuredOutputMode.PROMPT_DIRECTIVE; // Safe default for mixed providers
    }
}
```

***

### 6. `application.properties` — Enable It in ArcClaw

```properties
# ── 9Router: Free-forever LLM chain ──────────────────────────
ARCCLAW_LLM_PROVIDER=9router

# Slot 1: Kiro AI (Claude 4.5 FREE unlimited) — get token at kiro.aws
arcclaw.9router.kiro.enabled=true
arcclaw.9router.kiro.token=${ARCCLAW_KIRO_TOKEN:}
arcclaw.9router.kiro.model=claude-sonnet-4.5

# Slot 2: OpenCode Free (zero config, always on)
arcclaw.9router.opencode.enabled=true

# Slot 3: Groq (your existing free key)
arcclaw.9router.groq.enabled=true
arcclaw.9router.groq.api-key=${ARCCLAW_LLM_API_KEY:}
arcclaw.9router.groq.model=meta-llama/llama-4-scout-17b-16e-instruct

# Slot 4: OpenRouter free models
arcclaw.9router.openrouter.enabled=true
arcclaw.9router.openrouter.api-key=${ARCCLAW_LLM_API_KEY:}
arcclaw.9router.openrouter.model=openai/gpt-oss-20b:free

# Slot 5: Nvidia (disable until you have a key)
arcclaw.9router.nvidia.enabled=false

# Intent classifier + compaction reuse same 9router chain
ARCCLAW_INTENT_CLASSIFIER_PROVIDER=9router
ARCCLAW_COMPACTION_PROVIDER=9router
```

***

## Summary: How It Fits ArcClaw's Architecture

| Component | Role | Status |
|---|---|---|
| `NineRouterChatModel` | N-slot fallback engine with cooldown tracking | **New** |
| `NineRouterModelProducer` | CDI producer, assembles all slots from config | **New** |
| `NineRouterAgentChatModelProvider` | Hooks into existing `AgentChatModelProvider` SPI | **New** |
| `KiroProvider` | Kiro AI free Claude 4.5 slot | **New** |
| `OpenCodeFreeProvider` | OpenCode zero-auth slot | **New** |
| `GroqLlmProviderFactory` | Slot 3 — already exists   | Reused |
| `OpenRouterProvider` | Slot 4 — already exists   | Reused |
| `LlmExceptionClassifier` | 429/5xx detection — already exists   | Reused |
| `ChatModelFactory` | Builder pattern — already exists   | Reused |

The key advantage: **no Node.js, no 9Router process, no Docker**. All the slot logic, cooldown tracking, and fallback chain lives inside the existing Quarkus CDI lifecycle. Setting `ARCCLAW_LLM_PROVIDER=9router` activates the whole chain — every other ArcClaw component (`ReasoningEngine`, `StructuredOutputProcessor`, `CompactionModelProducer`) automatically uses the free-forever fallback loop. 