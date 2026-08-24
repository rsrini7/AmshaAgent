# ADR: Extract LLM Providers from amsha-core into Dedicated Maven Modules

- **Status**: Approved
- **Date**: 2026-08-19
- **Decision Owners**: Amsha Maintainers & Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §15.2, §16.1–16.3, §16.7, §20.1–20.2

---

## 1. Context and Problem Statement

`amsha-core` currently owns over 100 files of provider and integration code on top of the agent state machine, reasoning loop, memory tiers, security governance, and tool execution engines.

Inside `amsha-llm-provider-api`, two parallel SPI seams exist:
1. **Agent Reasoning SPI**: `com.amsha.llm.api.AgentChatModelProvider` (21 implementations in `amsha-llm-providers` + 1 internal `InfiniteLoopAgentChatModelProvider` in `core`)
2. **Internal Routing SPI**: `com.amsha.llm.api.RoutingChatModelProvider` (21 implementations in `amsha-llm-providers`)

Every provider is implemented twice (e.g. `KimchiProviderFactory` vs `KimchiLlmProviderFactory`), each averaging ~100 lines of code. Around them sit provider-private machinery:
- **5 Custom ChatModel Wrappers**: `AgyResponsesChatModel`, `CodexResponsesChatModel`, `KimchiResponsesChatModel`, `FoundryResponsesChatModel`, `ClaudeCodeResponsesChatModel`.
- **12 Resolvers / CLI Executors / OAuth Extractors**: `KimchiTokenResolver`, `KimchiOAuthTokenExtractor`, `AgyCliExecutor`, `AgyOAuthTokenExtractor`, `CodexOAuthTokenExtractor`, `ClaudeCodeOAuthTokenExtractor`, `FoundryDiscoveryHelper`, `FoundryCliExecutor`, etc.
- **Onboarding Catalog Package**: `LlmProviderOnboardingAdapter`, `StaticProviderCatalogAdapter`, `LlmProviderDescriptor`, and `provider-catalog.json`.
- **Shared Builder & Configuration Machinery**: `ChatModelFactory`, `ModelDefaults`, `LlmModelConfig`, `AgentLlmConfig`, `StructuredOutputMode`.
- **Provider Exception Hierarchy**: `GeminiExceptionClassifier` and related error classifiers.

### Architectural Drivers for Extraction
- **Eliminate Core Churn**: Adding or updating any model, token resolver, or provider currently touches `amsha-core`, triggering full core recompilation and test suites.
- **Decouple SDK Dependencies**: Provider SDKs (`quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ollama`, `quarkus-langchain4j-ai-gemini`) currently inflate `amsha-core`'s compile classpath.
- **Clear Separation of Concerns**: Downstream modules (`onboarding`, `rag`, `persistence-*`, `local-ml-onnx`) should only depend on lightweight API contracts, not heavy provider implementations.

---

## 2. Corrected Target Architecture & Dependency Graph

To prevent cyclic dependencies and maintain strict module boundaries, we adopt a **two-module target architecture**:

```
                 amsha-common
                      ▲
                      │
           amsha-llm-provider-api  (Contract: SPIs, DTOs, ModelDefaults, ChatModelCreationPort)
                 ▲          ▲
                 │          │
        ┌────────┴──┐     ┌─┴─────────────────────┐
        │ amsha-core│     │  amsha-llm-providers  │  (All 21 providers, SDKs, ChatModelFactory)
        └────────┬──┘     └─┬─────────────────────┘
                 ▲          ▲
                 │          │
                 └───┬──────┘
                     │
                 amsha-app  (Leaf Application Module)
```

### Module Responsibilities

| Module | Package Root | Responsibilities & Contents | Dependencies |
|---|---|---|---|
| **`amsha-llm-provider-api`** | `com.amsha.llm.api` | • **SPIs**: `AgentChatModelProvider`, `RoutingChatModelProvider`, `ChatModelCreationPort`<br>• **Contracts & Constants**: `ModelDefaults`, `LlmModelConfig`, `AgentLlmConfig`, `StructuredOutputMode`<br>• **Onboarding DTOs**: `LlmProviderOnboardingAdapter`, `LlmProviderDescriptor`, `ModelEntry`, `ConnectionValidationRequest`, `ProviderValidationResult`, `NetworkProfilePolicy`, `NetworkPolicyViolation`<br>• **Reflection**: `LlmProviderApiReflectionConfig` | `amsha-common`<br>`dev.langchain4j:langchain4j-core`<br>`quarkus-langchain4j-core`<br>`jackson-databind` |
| **`amsha-llm-providers`** | `com.amsha.llm.providers` | • **21 Concrete Factories**: All 21 `AgentChatModelProvider` and 21 `RoutingChatModelProvider` implementations<br>• **5 Custom Wrappers**: `Agy`, `Codex`, `Kimchi`, `Foundry`, `ClaudeCode`<br>• **12 Helpers**: Token resolvers, OAuth extractors, CLI executors<br>• **Catalog**: `StaticProviderCatalogAdapter` + `provider-catalog.json`<br>• **Factory Implementation**: `ChatModelFactory` implementing `ChatModelCreationPort`<br>• **Classifiers**: `GeminiExceptionClassifier`<br>• **Reflection**: `LlmProvidersReflectionConfig` | `amsha-llm-provider-api`<br>`amsha-core`<br>`quarkus-langchain4j-openai`<br>`quarkus-langchain4j-anthropic`<br>`quarkus-langchain4j-ollama`<br>`quarkus-langchain4j-ai-gemini` |
| **`amsha-core`** | `com.amsha.core` | • **Core Orchestration**: `ChatModelProducer`, `ModelRouter`, `ChatModelPool`, `LlmModelFactory`<br>• **Composite Wrappers**: `MultiModelChatModel`, `OrderedFailoverChatModel`, `InfiniteLoopChatModel`<br>• **Internal Loop Provider**: `InfiniteLoopAgentChatModelProvider`<br>• **Policy Enforcement**: `NetworkPolicyEnforcer`<br>• **Identity & Routing**: `ModelProfileResolverImpl`, `ModelProfileCredentialResolver`<br>• **Verification & Invariants**: `LangChain4jLlmAdapter` (§20.1), `LlmValidationService`, `IntentDetectorModelProducer` | `amsha-llm-provider-api`<br>`amsha-persistence-api`<br>`amsha-common` |

---

## 3. Resolution of Architectural Seams

### 1. `ChatModelFactory` Decoupling via `ChatModelCreationPort`
- **Problem**: `ChatModelProducer`, `ModelRouter`, and `LlmValidationService` in `amsha-core` call `ChatModelFactory.createForAdapter()`. `ChatModelFactory` directly imports LangChain4j provider SDKs (`OpenAiChatModel`, `AnthropicChatModel`, `OllamaChatModel`, `GeminiAiChatModel`).
- **Solution**:
  1. Define `ChatModelCreationPort` in `amsha-llm-provider-api`:
     ```java
     public interface ChatModelCreationPort {
         ChatModel createForAdapter(String provider, String baseUrl, String apiKey, String model, AgentLlmConfig config);
         boolean supportsAdapter(String provider);
     }
     ```
  2. Move `ChatModelFactory` to `amsha-llm-providers`, where it implements `ChatModelCreationPort` and retains the provider SDK dependencies.
  3. `amsha-core` injects `ChatModelCreationPort` (or `Instance<ChatModelCreationPort>`), allowing `amsha-core` to remove provider-specific LangChain4j SDK dependencies entirely.

### 2. Composite Wrappers Stay in `amsha-core`
- **Problem**: `MultiModelChatModel` and `OrderedFailoverChatModel` import `WorkspaceManager`, `WorkspaceContext`, and `com.amsha.core.agent.reasoning.error.*`. Moving them to `amsha-llm-provider-api` would violate the zero-core-dependency rule.
- **Solution**: Keep `MultiModelChatModel`, `OrderedFailoverChatModel`, and `InfiniteLoopChatModel` in `amsha-core`. `amsha-llm-provider-api` remains a pure contract module with zero core dependencies.

### 3. Policy Enforcement Boundary
- **Problem**: `ModelProfileCredentialResolver` in core injects `NetworkPolicyEnforcer`, which uses `OutboundHttpPolicyService` and `persistence-api`.
- **Solution**:
  - `NetworkProfilePolicy` and `NetworkPolicyViolation` live in `amsha-llm-provider-api` as pure DTOs.
  - `NetworkPolicyEnforcer` remains in `amsha-core` alongside `OutboundHttpPolicyService`.
  - `StaticProviderCatalogAdapter` and `provider-catalog.json` move to `amsha-llm-providers`.

### 4. Provider Inventory Clarification (21 vs 22)
- In the current codebase:
  - `RoutingChatModelProvider`: exactly 21 concrete implementations (all 21 move to `amsha-llm-providers`).
  - `AgentChatModelProvider`: 22 implementations (21 concrete providers + 1 `InfiniteLoopAgentChatModelProvider`).
  - `InfiniteLoopAgentChatModelProvider` represents internal state-machine fallback orchestration, not an external provider, and **remains in `amsha-core`**.
  - Exactly 21 external provider beans will be discovered from `amsha-llm-providers` at runtime startup.

---

## 4. Addressing Invariants & Failure Modes

### §20.1 Implementation Proximity & Native CDI Resolution
- **Historical Incident**: §20.1 documents that placing singleton SPI implementations outside core caused `UnsatisfiedResolutionException` during native image build validation.
- **Mitigation**:
  1. `ChatModelProducer` and `ModelRouter` inject provider factories via `Instance<AgentChatModelProvider>` and `Instance<RoutingChatModelProvider>`. `Instance<T>` tolerates multi-bean discovery without failing build-time singleton validation.
  2. `ChatModelCreationPort` is implemented as an `@ApplicationScoped @Unremovable` bean in `amsha-llm-providers`.
  3. §20.1 in `docs/Architecture_Invariants.md` is updated to document the multi-implementation `Instance<T>` exception for provider plugins.

### §15.2 / §16.3 Jandex and `beans.xml` CDI Discovery
- Both `amsha-llm-provider-api` and `amsha-llm-providers` must contain `META-INF/beans.xml` with `bean-discovery-mode="annotated"`.
- Both modules must configure `jandex-maven-plugin` (`make-index` execution).
- Every provider factory bean must be annotated with `@ApplicationScoped` and `@io.quarkus.arc.Unremovable`.

### §16.7 Application Dependency Indexing
Per `docs/Architecture_Invariants.md` §16.7, both new modules must be registered in `app/src/main/resources/application.yaml` under `quarkus.index-dependency`:
```yaml
quarkus:
  index-dependency:
    llm-provider-api:
      group-id: com.amsha
      artifact-id: amsha-llm-provider-api
    llm-providers:
      group-id: com.amsha
      artifact-id: amsha-llm-providers
```

### Native Image Reflection Configuration
- `amsha-llm-provider-api` defines `LlmProviderApiReflectionConfig` to register all onboarding/connection DTOs and records.
- `amsha-llm-providers` defines `LlmProvidersReflectionConfig` to register all 21 factory classes, 5 custom wrappers, 12 helpers, and nested classes (such as `StaticProviderCatalogAdapter$RawProvider`).

---

## 5. Revised Step-by-Step Sequence

### Step 1: Architectural Fences & Baseline
1. Record passing baseline: `./mvnw clean install -DskipTests` + PMD + `python3 ./scripts/verify_docs.py`.
2. Add `ArchUnit` boundary tests in `app/src/test/java/com/amsha/app/ArchitectureBoundaryTest.java`:
   - `amsha-core` must **not** import `com.amsha.llm.providers..`.
   - `amsha-llm-providers` must **not** import `com.amsha.app..`.

### Step 2: Create `amsha-llm-provider-api`
1. Create `llm-provider-api/` with `pom.xml`, `META-INF/beans.xml`, and `jandex-maven-plugin`.
2. Move SPIs (`AgentChatModelProvider`, `RoutingChatModelProvider`, `ChatModelCreationPort`), model config records (`LlmModelConfig`, `AgentLlmConfig`, `StructuredOutputMode`, `ModelDefaults`), and pure onboarding DTOs.
3. Create `LlmProviderApiReflectionConfig`.
4. Add `amsha-llm-provider-api` dependency to `amsha-core`.
5. Run: `./mvnw -pl llm-provider-api,core -am spotless:apply -q && ./mvnw -pl llm-provider-api,core -am pmd:check -DskipTests`.

### Step 3: Decouple Core via `ChatModelCreationPort`
1. In `amsha-core`, refactor `ChatModelProducer`, `ModelRouter`, `LlmValidationService`, and `IntentDetectorModelProducer` to inject `ChatModelCreationPort` instead of invoking static methods on `ChatModelFactory`.
2. Keep `MultiModelChatModel`, `OrderedFailoverChatModel`, `InfiniteLoopChatModel`, `InfiniteLoopAgentChatModelProvider`, and `NetworkPolicyEnforcer` in `amsha-core`.

### Step 4: Create `amsha-llm-providers` and Move Implementations
1. Create `llm-providers/` with `pom.xml`, `META-INF/beans.xml`, `jandex-maven-plugin`, and `src/test/resources/application.yaml`.
2. Move all 21+21 concrete factory classes, 5 custom `*ResponsesChatModel` wrappers, 12 CLI/token helpers, `StaticProviderCatalogAdapter`, and `provider-catalog.json`.
3. Move `ChatModelFactory` to `amsha-llm-providers` (implementing `ChatModelCreationPort`).
4. Move LangChain4j provider SDK dependencies (`quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ollama`, `quarkus-langchain4j-ai-gemini`) from `amsha-core` to `amsha-llm-providers`.
5. Create `LlmProvidersReflectionConfig` in `amsha-llm-providers`.
6. Move `AgentProviderFactoryCoverageTest` and `RoutingProviderFactoryCoverageTest` to `llm-providers`.

### Step 5: Wire `amsha-app` and Register Indexing
1. Add `amsha-llm-provider-api` and `amsha-llm-providers` to `app/pom.xml`.
2. Add both modules to `app/src/main/resources/application.yaml` under `quarkus.index-dependency`.
3. Clean up `CoreReflectionConfig` in `amsha-core`.

### Step 6: Update Consumers & Downstream Modules
1. Update `onboarding`, `rag`, `persistence-*`, `local-ml-onnx` POMs to depend on `amsha-llm-provider-api` (and `amsha-llm-providers` where runtime beans are required).
2. Update all imports from `com.amsha.core.routing.ModelDefaults` to `com.amsha.llm.api.ModelDefaults`.

### Step 7: Verification and CDI Proof Gate
1. **Formatting & Lints**: `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`.
2. **Module-Scoped PMD Gate**: `./mvnw -pl llm-provider-api,llm-providers,core,onboarding,app -am pmd:check -DskipTests` (0 violations across all modules).
3. **Unit & Integration Test Suite**: `./mvnw test -pl llm-provider-api,llm-providers,core,onboarding,app`.
4. **JVM Startup CDI Proof**: Launch `./mvnw quarkus:dev` or `ChatModelProducerTest` and verify startup logs confirm all 21 providers discovered.
5. **Native Build & Smoke Gate**: Run `mise run build:native-local` followed by `mise run smoke:local-runtime -- native --binary dist/amsha-sqlite`.

---

## 6. Strategic Decisions Summary

| Decision | Issue | Chosen Approach | Rationale |
|---|---|---|---|
| **D1** | Module Split Scope | **Two Modules** (`amsha-llm-provider-api` + `amsha-llm-providers`) | Enforces strict separation between API contracts and concrete SDK dependencies, preventing cyclic dependency graphs. |
| **D2** | Provider Factory Port | **Introduce `ChatModelCreationPort`** | Decouples `amsha-core` from LangChain4j provider SDK dependencies while maintaining type-safe adapter creation. |
| **D3** | Composite Wrappers Location | **Keep in `amsha-core`** | `MultiModelChatModel` and `OrderedFailoverChatModel` depend on core workspace and error types; keeping them in core keeps the API module 100% pure. |
| **D4** | Provider Inventory | **21 Providers in `llm-providers`, 1 in Core** | 21 concrete providers move to `llm-providers`. `InfiniteLoopAgentChatModelProvider` remains in core as internal fallback logic. |
| **D5** | SPI Unification Timing | **Follow-up** | Unifying `AgentChatModelProvider` and `RoutingChatModelProvider` into one self-describing SPI will be done after extraction stability is proven. |

---

## 7. Verification Checklist

- [x] `amsha-llm-provider-api` compiles with zero core dependencies.
- [x] `ChatModelCreationPort` successfully abstracts provider creation from `amsha-core`.
- [x] `amsha-llm-providers` compiles with all 21 concrete providers and passes test suites.
- [x] ArchUnit tests in `amsha-app` enforce import and package boundaries.
- [x] `quarkus.index-dependency` in `application.yaml` includes both new modules.
- [x] `./mvnw -pl llm-provider-api,llm-providers,core,app -am pmd:check -DskipTests` passes with 0 violations.
- [x] Startup logs confirm CDI discovery of all 21 providers in both JVM and native modes.
- [x] Native binary packages cleanly (`mise run build:native-local`) and passes local runtime smoke tests.
- [x] `python3 ./scripts/verify_docs.py` passes with 0 errors and 0 warnings.
