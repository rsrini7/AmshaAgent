Hermes Agent Self-Evolution and MemGPT solve different problems, even though both touch memory and agent improvement. **MemGPT is mainly a memory architecture for keeping long-running context usable; Hermes is mainly a self-improvement pipeline for evolving skills, prompts, and tool descriptions from execution traces.** [github](https://github.com/NousResearch/hermes-agent-self-evolution/blob/main/PLAN.md)

## Core difference

MemGPT’s central idea is tiered memory: keep a small core context active, retrieve older conversation/history from recall memory, and move long-lived knowledge into archival memory. The goal is to fight context loss and the “lost in the middle” problem. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)

Hermes Agent Self-Evolution is a closed-loop optimization system that uses DSPy + GEPA to automatically improve the agent itself from real traces. The goal is not just remembering better, but **becoming a better agent over time** by evolving the skill files, tool descriptions, and prompts. [github](https://github.com/NousResearch/hermes-agent-self-evolution)

## Memory vs learning

MemGPT treats memory as a runtime system: what should stay in context, what should be retrieved, and what should be archived. It exposes explicit memory tools so the agent can manage its own context. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy/)

Hermes treats memory more as training data for optimization. It records execution traces and uses them to search for improvements to the agent’s behavior, similar to prompt/skill evolution rather than context management. [reddit](https://www.reddit.com/r/hermesagent/comments/1t5ifvg/nous_research_just_dropped_hermes_agent/)

## Operational shape

MemGPT is about **state management during the conversation**:
- preserve current task state,
- search past interactions,
- maintain long-term facts. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)

Hermes is about **system evolution across runs**:
- analyze traces,
- mutate skills/prompts/tool descriptions,
- produce a better agent version. [github](https://github.com/NousResearch/hermes-agent-self-evolution/blob/main/PLAN.md)

So in plain terms:
- MemGPT asks: “How do I not forget?”
- Hermes asks: “How do I improve myself from what I did?” [github](https://github.com/NousResearch/hermes-agent-self-evolution)

## What this means for ArcClaw

For ArcClaw, MemGPT-style tiered memory is the better fit if your current pain is lost code, lost errors, or lost user goals. Hermes-style self-evolution is the better fit if your pain is that the agent repeatedly makes the same mistakes and you want an offline improvement loop. [github](https://github.com/NousResearch/hermes-agent-self-evolution/blob/main/PLAN.md)

A useful way to combine them is:
- use **MemGPT-like memory** at runtime,
- use **Hermes-like trace evolution** offline to improve prompts, tool docs, and guardrails. [github](https://github.com/NousResearch/hermes-agent-self-evolution)

## Recommendation

If you are choosing one direction first, pick **MemGPT-style memory** for now. It directly addresses context retention and exact detail recovery, which is the problem you’ve been describing. Hermes is a second-stage capability once you have enough traces and stable workflows to evolve from. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)

---

Found Java:

https://github.com/openmemind/memind

That is the **right** approach for ArcClaw self-evolve: do not pull Memind as a dependency; copy only the ideas and re-implement a very small ArcClaw-native module. That matches your stated preference for minimal integration, clean module boundaries, and avoiding circular or heavy framework dependencies in ArcClaw’s Quarkus architecture. 

## What to keep

From Memind, the most reusable ideas are the architectural shapes, not the concrete codebase. Its core API revolves around three concerns — add conversation content, commit/extract memory, and retrieve memory — while the default implementation internally composes extractor, retriever, store, buffer, and optional insight logic. 

For ArcClaw self-evolve, keep only these concepts:
- A small `MemoryLikeService` facade with `append`, `flush`, and `search`. 
- A pending/recent conversation buffer so you can accumulate turns before extracting. 
- Two retrieval modes: simple fast retrieval first, deeper synthesis later; Memind itself distinguishes low-latency SIMPLE retrieval from heavier DEEP retrieval. 
- Optional “interaction guide” synthesis later, because Memind explicitly generates agent-facing directives from prior behavior and preferences, which is directly relevant to self-evolve. 

## What to drop

Do **not** carry over the full Memind stack into ArcClaw self-evolve. The source shows a large reactive, plugin-heavy design with Reactor `Mono`, Spring/Spring-AI oriented modules, retrieval trace plumbing, rerankers, vector layers, JDBC starters, and a standalone server/client split, which is far more than you need for a first ArcClaw-native module. 

I would drop these from v1:
- Reactor-first async API. 
- Multi-tier retrieval orchestration with query rewriting, reranking, graph assist, and trace stages. 
- Generic raw-content ingestion framework for files/URLs/tool payloads. 
- Dedicated server/client model and Spring Boot starter path. 

That keeps self-evolve small, testable, and native to your existing ArcClaw style. [perplexity](https://www.perplexity.ai/search/ca25d9be-e10c-4df0-8ba0-cf0f1c5a306e)

## ArcClaw module shape

Make `arcclaw-self-evolve` a plain internal module with its own SPI and one active backend at startup, which aligns with your existing preference for selectable backends and minimal coupling.  Keep the module depending only on ArcClaw core abstractions and standard Java/Quarkus pieces, not on app-layer code, because you have already been careful to avoid core-to-app dependency leakage. [perplexity](https://www.perplexity.ai/search/ca25d9be-e10c-4df0-8ba0-cf0f1c5a306e)

A clean shape would be:
- `SelfEvolveService` — main facade used by agent/runtime code.
- `EvolutionStore` — SPI for SQLite, Postgres, or filesystem mode, one active at startup.
- `ConversationBuffer` — recent turns plus pending commit window.
- `MemoryExtractorLite` — converts turns into compact facts/preferences/tasks.
- `MemoryRetrieverLite` — keyword/FTS lookup first, optional embeddings later.
- `InteractionPolicySynthesizer` — converts stable patterns into agent directives.

That gives you the Memind value without Memind’s dependency surface. 

## Minimal v1 logic

For v1, implement only a lightweight pipeline:

1. Append chat/tool events into a session buffer.  
2. On boundary triggers such as session end, idle gap, or explicit flush, summarize into atomic memory records. 
3. Store records with fields like `memoryId`, `type`, `text`, `tags`, `timestamp`, `confidence`, and `source`.  
4. At prompt-build time, retrieve top relevant records plus a few stable directives.

This is enough to support self-evolve behavior like:
- remembering user preferences,
- remembering successful resolutions,
- remembering repeated failure/repair patterns,
- adapting future prompts with short durable directives.

Memind’s own prompts separate descriptive synthesis from prescriptive interaction directives, and that split is worth copying into ArcClaw because it prevents mixing “what happened” with “how the agent should behave.” 

## Concrete recommendation

My recommendation is to build ArcClaw self-evolve in **three phases**:

| Phase | Build now | Why |
|---|---|---|
| 1 | Buffer + flush + atomic memory store | Smallest useful system, easy to validate.   |
| 2 | Simple retrieval + prompt injection | Gives immediate product value without deep AI orchestration.   |
| 3 | Directive synthesis + periodic compaction | This is the real “self-evolve” layer.   |

If you do that, ArcClaw gets the best 20% of Memind’s ideas with maybe 5% of its complexity.  My strongest advice is: **do not port classes**; port only the patterns — especially buffer/commit/retrieve and the later profile-vs-directive split. 

## Suggested scope

A good ArcClaw-native v1 package layout would be:

- `com.arcclaw.selfevolve.api`
- `com.arcclaw.selfevolve.buffer`
- `com.arcclaw.selfevolve.extract`
- `com.arcclaw.selfevolve.retrieve`
- `com.arcclaw.selfevolve.store`
- `com.arcclaw.selfevolve.policy`

And the first interfaces should be tiny:
- `SelfEvolveService.append(Event event)`
- `SelfEvolveService.flush(SessionKey key)`
- `SelfEvolveService.search(UserKey key, String query)`
- `SelfEvolveService.buildDirectives(UserKey key)`

That stays consistent with your modular, SPI-first approach and avoids turning ArcClaw self-evolve into a second framework inside your app.

---

Here is a clean ArcClaw-style draft for `arcclaw-self-evolve`: small API surface, SPI-first backend, Quarkus-friendly services, and no framework leakage.

The shape below follows the modular/service-provider pattern you’ve been discussing: one facade for app use, narrow internal contracts, and one active store backend selected by configuration at startup. That matches standard Java SPI/API separation and Quarkus config-driven wiring patterns. [quarkus](https://quarkus.io/guides/config-reference)

## Package layout

```text
arcclaw-self-evolve/
└── src/main/java/com/arcclaw/selfevolve/
    ├── api/
    │   ├── SelfEvolveService.java
    │   ├── SelfEvolveQuery.java
    │   ├── SelfEvolveResult.java
    │   ├── DirectiveBundle.java
    │   ├── MemoryRecord.java
    │   ├── MemoryRecordType.java
    │   ├── EvolutionEvent.java
    │   ├── SessionKey.java
    │   └── UserKey.java
    ├── buffer/
    │   ├── ConversationBuffer.java
    │   ├── BufferedSession.java
    │   └── InMemoryConversationBuffer.java
    ├── extract/
    │   ├── MemoryExtractorLite.java
    │   ├── ExtractedMemory.java
    │   └── DefaultMemoryExtractorLite.java
    ├── retrieve/
    │   ├── MemoryRetrieverLite.java
    │   ├── RetrievalMode.java
    │   ├── RetrievalRequest.java
    │   ├── RetrievalHit.java
    │   └── DefaultMemoryRetrieverLite.java
    ├── policy/
    │   ├── InteractionPolicySynthesizer.java
    │   ├── SynthesizedDirective.java
    │   └── DefaultInteractionPolicySynthesizer.java
    ├── store/
    │   ├── MemoryStore.java
    │   ├── MemoryStoreProvider.java
    │   ├── StoreHealth.java
    │   ├── sqlite/
    │   │   └── SqliteMemoryStore.java
    │   ├── postgres/
    │   │   └── PostgresMemoryStore.java
    │   └── fs/
    │       └── FilesystemMemoryStore.java
    ├── runtime/
    │   ├── DefaultSelfEvolveService.java
    │   ├── SelfEvolveBackendSelector.java
    │   ├── SelfEvolveConfig.java
    │   └── FlushTriggerPolicy.java
    └── internal/
        └── MemoryRecordMapper.java
```

## Core API

### `SelfEvolveService`

```java
package com.arcclaw.selfevolve.api;

import java.util.List;
import java.util.Optional;

public interface SelfEvolveService {

    void append(EvolutionEvent event);

    FlushResult flush(SessionKey sessionKey);

    List<SelfEvolveResult> search(UserKey userKey, String query);

    List<SelfEvolveResult> search(SelfEvolveQuery query);

    DirectiveBundle buildDirectives(UserKey userKey);

    Optional<MemoryRecord> getById(String memoryId);
}
```

### `EvolutionEvent`

```java
package com.arcclaw.selfevolve.api;

import java.time.Instant;
import java.util.Map;

public record EvolutionEvent(
        String eventId,
        SessionKey sessionKey,
        UserKey userKey,
        EvolutionEventType type,
        String role,
        String content,
        Map<String, Object> metadata,
        Instant timestamp
) {}
```

### `EvolutionEventType`

```java
package com.arcclaw.selfevolve.api;

public enum EvolutionEventType {
    USER_MESSAGE,
    AGENT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    RESOLUTION,
    SYSTEM_NOTE
}
```

### `SessionKey`

```java
package com.arcclaw.selfevolve.api;

public record SessionKey(String value) {}
```

### `UserKey`

```java
package com.arcclaw.selfevolve.api;

public record UserKey(String value) {}
```

### `MemoryRecord`

```java
package com.arcclaw.selfevolve.api;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record MemoryRecord(
        String memoryId,
        UserKey userKey,
        SessionKey sessionKey,
        MemoryRecordType type,
        String text,
        Set<String> tags,
        double confidence,
        String source,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt
) {}
```

### `MemoryRecordType`

```java
package com.arcclaw.selfevolve.api;

public enum MemoryRecordType {
    USER_PREFERENCE,
    USER_PROFILE,
    ACTIVE_TASK,
    RESOLUTION_PATTERN,
    FAILURE_PATTERN,
    TOOL_HINT,
    PROJECT_CONTEXT,
    DIRECTIVE,
    SUMMARY,
    FACT
}
```

### `SelfEvolveQuery`

```java
package com.arcclaw.selfevolve.api;

import java.util.Set;

import com.arcclaw.selfevolve.retrieve.RetrievalMode;

public record SelfEvolveQuery(
        UserKey userKey,
        String text,
        RetrievalMode mode,
        int limit,
        Set<MemoryRecordType> types,
        Set<String> tags
) {}
```

### `SelfEvolveResult`

```java
package com.arcclaw.selfevolve.api;

public record SelfEvolveResult(
        MemoryRecord record,
        double score,
        String rationale
) {}
```

### `DirectiveBundle`

```java
package com.arcclaw.selfevolve.api;

import java.util.List;

public record DirectiveBundle(
        List<String> stableDirectives,
        List<String> recentInsights,
        List<MemoryRecord> supportingMemories
) {}
```

### `FlushResult`

```java
package com.arcclaw.selfevolve.api;

import java.util.List;

public record FlushResult(
        SessionKey sessionKey,
        int bufferedEvents,
        int extractedMemories,
        List<String> createdMemoryIds
) {}
```

## Buffer contracts

### `ConversationBuffer`

```java
package com.arcclaw.selfevolve.buffer;

import java.util.List;
import java.util.Optional;

import com.arcclaw.selfevolve.api.EvolutionEvent;
import com.arcclaw.selfevolve.api.SessionKey;

public interface ConversationBuffer {

    void append(EvolutionEvent event);

    Optional<BufferedSession> get(SessionKey sessionKey);

    List<EvolutionEvent> drain(SessionKey sessionKey);

    int size(SessionKey sessionKey);

    void clear(SessionKey sessionKey);
}
```

### `BufferedSession`

```java
package com.arcclaw.selfevolve.buffer;

import java.time.Instant;
import java.util.List;

import com.arcclaw.selfevolve.api.EvolutionEvent;
import com.arcclaw.selfevolve.api.SessionKey;
import com.arcclaw.selfevolve.api.UserKey;

public record BufferedSession(
        SessionKey sessionKey,
        UserKey userKey,
        List<EvolutionEvent> events,
        Instant updatedAt
) {}
```

## Extract contracts

### `MemoryExtractorLite`

```java
package com.arcclaw.selfevolve.extract;

import java.util.List;

import com.arcclaw.selfevolve.api.EvolutionEvent;
import com.arcclaw.selfevolve.api.SessionKey;
import com.arcclaw.selfevolve.api.UserKey;

public interface MemoryExtractorLite {

    List<ExtractedMemory> extract(UserKey userKey, SessionKey sessionKey, List<EvolutionEvent> events);
}
```

### `ExtractedMemory`

```java
package com.arcclaw.selfevolve.extract;

import java.util.Map;
import java.util.Set;

import com.arcclaw.selfevolve.api.MemoryRecordType;

public record ExtractedMemory(
        MemoryRecordType type,
        String text,
        Set<String> tags,
        double confidence,
        String source,
        Map<String, Object> attributes
) {}
```

## Retrieval contracts

### `MemoryRetrieverLite`

```java
package com.arcclaw.selfevolve.retrieve;

import java.util.List;

import com.arcclaw.selfevolve.api.SelfEvolveQuery;
import com.arcclaw.selfevolve.api.SelfEvolveResult;

public interface MemoryRetrieverLite {

    List<SelfEvolveResult> search(SelfEvolveQuery query);
}
```

### `RetrievalMode`

```java
package com.arcclaw.selfevolve.retrieve;

public enum RetrievalMode {
    SIMPLE,
    DEEP
}
```

### `RetrievalRequest`

```java
package com.arcclaw.selfevolve.retrieve;

import java.util.Set;

import com.arcclaw.selfevolve.api.MemoryRecordType;
import com.arcclaw.selfevolve.api.UserKey;

public record RetrievalRequest(
        UserKey userKey,
        String query,
        RetrievalMode mode,
        int limit,
        Set<MemoryRecordType> types,
        Set<String> tags
) {}
```

### `RetrievalHit`

```java
package com.arcclaw.selfevolve.retrieve;

public record RetrievalHit(
        String memoryId,
        double score,
        String rationale
) {}
```

## Directive/policy contracts

### `InteractionPolicySynthesizer`

```java
package com.arcclaw.selfevolve.policy;

import java.util.List;

import com.arcclaw.selfevolve.api.DirectiveBundle;
import com.arcclaw.selfevolve.api.MemoryRecord;
import com.arcclaw.selfevolve.api.UserKey;

public interface InteractionPolicySynthesizer {

    DirectiveBundle synthesize(UserKey userKey, List<MemoryRecord> memories);
}
```

### `SynthesizedDirective`

```java
package com.arcclaw.selfevolve.policy;

public record SynthesizedDirective(
        String text,
        double confidence,
        String source
) {}
```

## Store SPI

### `MemoryStore`

```java
package com.arcclaw.selfevolve.store;

import java.util.List;
import java.util.Optional;

import com.arcclaw.selfevolve.api.MemoryRecord;
import com.arcclaw.selfevolve.api.SelfEvolveQuery;
import com.arcclaw.selfevolve.api.UserKey;

public interface MemoryStore {

    void upsert(List<MemoryRecord> records);

    List<MemoryRecord> search(SelfEvolveQuery query);

    List<MemoryRecord> findByUser(UserKey userKey, int limit);

    Optional<MemoryRecord> getById(String memoryId);

    StoreHealth health();
}
```

### `MemoryStoreProvider`

```java
package com.arcclaw.selfevolve.store;

public interface MemoryStoreProvider {

    String backendId();

    MemoryStore create();
}
```

### `StoreHealth`

```java
package com.arcclaw.selfevolve.store;

public record StoreHealth(
        boolean healthy,
        String backend,
        String detail
) {}
```

## Runtime wiring

### `SelfEvolveConfig`

```java
package com.arcclaw.selfevolve.runtime;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "arcclaw.self-evolve")
public interface SelfEvolveConfig {

    boolean enabled();

    String backend();

    int bufferSize();

    int defaultSearchLimit();

    boolean directivesEnabled();

    Flush flush();

    interface Flush {
        int maxEvents();
        long idleMs();
    }
}
```

### `SelfEvolveBackendSelector`

```java
package com.arcclaw.selfevolve.runtime;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.selfevolve.store.MemoryStore;
import com.arcclaw.selfevolve.store.MemoryStoreProvider;

@ApplicationScoped
public class SelfEvolveBackendSelector {

    @Inject
    SelfEvolveConfig config;

    @Inject
    List<MemoryStoreProvider> providers;

    public MemoryStore select() {
        return providers.stream()
                .filter(p -> p.backendId().equalsIgnoreCase(config.backend()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No self-evolve backend for: " + config.backend()))
                .create();
    }
}
```

### `DefaultSelfEvolveService`

```java
package com.arcclaw.selfevolve.runtime;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.selfevolve.api.*;
import com.arcclaw.selfevolve.buffer.ConversationBuffer;
import com.arcclaw.selfevolve.extract.MemoryExtractorLite;
import com.arcclaw.selfevolve.policy.InteractionPolicySynthesizer;
import com.arcclaw.selfevolve.store.MemoryStore;

@ApplicationScoped
public class DefaultSelfEvolveService implements SelfEvolveService {

    @Inject
    ConversationBuffer buffer;

    @Inject
    MemoryExtractorLite extractor;

    @Inject
    InteractionPolicySynthesizer synthesizer;

    @Inject
    SelfEvolveBackendSelector selector;

    @Override
    public void append(EvolutionEvent event) {
        buffer.append(event);
    }

    @Override
    public FlushResult flush(SessionKey sessionKey) {
        throw new UnsupportedOperationException("draft");
    }

    @Override
    public List<SelfEvolveResult> search(UserKey userKey, String query) {
        return search(new SelfEvolveQuery(userKey, query, com.arcclaw.selfevolve.retrieve.RetrievalMode.SIMPLE, 8, java.util.Set.of(), java.util.Set.of()));
    }

    @Override
    public List<SelfEvolveResult> search(SelfEvolveQuery query) {
        throw new UnsupportedOperationException("draft");
    }

    @Override
    public DirectiveBundle buildDirectives(UserKey userKey) {
        throw new UnsupportedOperationException("draft");
    }

    @Override
    public Optional<MemoryRecord> getById(String memoryId) {
        return selector.select().getById(memoryId);
    }
}
```

## Backend skeletons

### SQLite

```java
package com.arcclaw.selfevolve.store.sqlite;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.selfevolve.store.MemoryStore;
import com.arcclaw.selfevolve.store.MemoryStoreProvider;

@ApplicationScoped
public class SqliteMemoryStoreProvider implements MemoryStoreProvider {

    @Override
    public String backendId() {
        return "sqlite";
    }

    @Override
    public MemoryStore create() {
        return new SqliteMemoryStore();
    }
}
```

### Postgres

```java
package com.arcclaw.selfevolve.store.postgres;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.selfevolve.store.MemoryStore;
import com.arcclaw.selfevolve.store.MemoryStoreProvider;

@ApplicationScoped
public class PostgresMemoryStoreProvider implements MemoryStoreProvider {

    @Override
    public String backendId() {
        return "postgres";
    }

    @Override
    public MemoryStore create() {
        return new PostgresMemoryStore();
    }
}
```

### Filesystem

```java
package com.arcclaw.selfevolve.store.fs;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.selfevolve.store.MemoryStore;
import com.arcclaw.selfevolve.store.MemoryStoreProvider;

@ApplicationScoped
public class FilesystemMemoryStoreProvider implements MemoryStoreProvider {

    @Override
    public String backendId() {
        return "fs";
    }

    @Override
    public MemoryStore create() {
        return new FilesystemMemoryStore();
    }
}
```

## Config keys

```yaml
arcclaw:
  self-evolve:
    enabled: ${ARCCLAW_SELF_EVOLVE_ENABLED:false}
    backend: ${ARCCLAW_SELF_EVOLVE_BACKEND:sqlite}
    buffer-size: ${ARCCLAW_SELF_EVOLVE_BUFFER_SIZE:50}
    default-search-limit: ${ARCCLAW_SELF_EVOLVE_DEFAULT_SEARCH_LIMIT:8}
    directives-enabled: ${ARCCLAW_SELF_EVOLVE_DIRECTIVES_ENABLED:true}
    flush:
      max-events: ${ARCCLAW_SELF_EVOLVE_FLUSH_MAX_EVENTS:12}
      idle-ms: ${ARCCLAW_SELF_EVOLVE_FLUSH_IDLE_MS:300000}
```

## ArcClaw-style notes

A few ArcClaw-specific recommendations:
- Keep **API** records small and immutable; ArcClaw already trends that way in other modules. [stackoverflow](https://stackoverflow.com/questions/11404230/how-to-implement-the-api-spi-pattern-in-java)
- Put backend selection in **runtime**, not in `api` or `store`. [quarkus](https://quarkus.io/guides/config)
- Use one active backend at startup, fail fast if misconfigured. [stackoverflow](https://stackoverflow.com/questions/79365320/quarkus-user-configurable-backend-database)
- Start with **SIMPLE retrieval only** in v1; keep `DEEP` as an enum and extension point, not a full implementation yet. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)
- Keep directive synthesis separate from retrieval so “memory facts” and “behavior guidance” don’t collapse into one blob. [github](https://github.com/openmemind/memind)

If you want, I can next draft the **exact Maven module skeleton and `application.yaml` section**, or generate the **actual Java files** for this package layout.