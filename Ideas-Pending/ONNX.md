Here is the complete picture — what ONNX is, all relevant ArcClaw use cases, and exactly how to integrate it.

***

## What ONNX Runtime Is

**ONNX (Open Neural Network Exchange)** is an open format for representing machine learning models. ONNX Runtime is Microsoft's inference engine that executes these models. The workflow is: [infoq](https://www.infoq.com/articles/onnx-ai-inference-with-java/)

```
Python (once, at dev time)              Java/Quarkus (production)
──────────────────────────              ──────────────────────────
Train or download any model      →      Load .onnx file
Export to .onnx via Optimum CLI         Run inference via ORT Java API
                                        No Python. No cloud. No API call.
```

Key properties relevant to ArcClaw: [the-main-thread](https://www.the-main-thread.com/p/quarkus-onnx-pgvector-image-search-tutorial)
- Pure JVM inference — no Python runtime at all in production
- CPU inference works out of the box, GPU optional via execution providers
- Native image compatible (ships native `.so` bundled in the JAR, loaded via JNI)
- ~20–50ms inference latency for small transformer models
- **Important**: only FP32 models work with the Java binding — quantized INT8 models require platform execution providers not included in the default JAR [the-main-thread](https://www.the-main-thread.com/p/quarkus-onnx-pgvector-image-search-tutorial)

***

## ArcClaw-Specific Use Cases

### Use Case 1 — Local Intent Classification (replaces LLM call)

Your `IntentClassificationHandler` currently calls an LLM to classify short messages like "do the same in java" → `coder`. This is wasteful — a 30MB ONNX classifier does it in ~5ms locally. [infoq](https://www.infoq.com/articles/onnx-ai-inference-with-java/)

```
Current:  message → LLM API call (~800ms, costs tokens) → role
With ONNX: message → tokenizer → ONNX classifier (~5ms, free) → role
LLM fallback: only for genuinely ambiguous messages
```

Export: `all-MiniLM-L6-v2` fine-tuned on your own intent labels (`coder`, `coordinator`, `researcher`, etc.)

***

### Use Case 2 — Local Embeddings for RAG (replaces remote embedding API)

ArcClaw uses SQLite for memory storage. Embeddings currently go through Jina's embedding API — every `memory_search` or `ingest_document` call costs a remote round trip. [milvus](https://milvus.io/blog/no-python-no-problem-model-inference-with-onnx-in-java-or-any-other-language.md)

```
With ONNX: sentence-transformers/all-MiniLM-L6-v2 → .onnx
           Produces 384-dim vectors locally in ~20ms
           Plugs directly into LangChain4J's EmbeddingModel interface
```

## What Changes

**Current flow:**
```
text → HTTP POST api.jina.ai/v1/embeddings → 768-dim vector
       (~200-400ms, costs API credits, requires internet)
```

**With ONNX:**
```
text → LocalEmbeddingModel (ONNX) → 384-dim vector
       (~20ms, free, fully offline, single binary)
```

## One Thing to Verify Before Swapping

Jina v2 produces **768-dim** vectors. `all-MiniLM-L6-v2` produces **384-dim**. If your SQLite vector store already has embeddings stored from Jina, they're **incompatible** — you'd need to re-embed all existing documents after switching.

Options:
- **Clean swap** — re-embed everything, use ONNX going forward
- **Parallel run** — keep Jina for existing data, use ONNX for new ingestions, migrate gradually
- **Use a 768-dim ONNX model** — `sentence-transformers/all-mpnet-base-v2` produces 768-dim, matching Jina's output dimension (though not the same vector space — still needs re-embedding)

## ONNX Model Closest to Jina v2 Quality

```bash
# Best quality/size tradeoff matching Jina's multilingual capability
optimum-cli export onnx \
  --model sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2 \
  --task feature-extraction \
  ./models/embeddings/
```

This is 384-dim but multilingual — if ArcClaw only handles English, `all-MiniLM-L6-v2` is faster and sufficient. Everything else in Step 3 of the integration stays exactly the same.

***

### Use Case 3 — Destructive Vocabulary / Toxic Content Filter

Your `DestructiveVocabularyFilter` in the pipeline is likely keyword-based today. A small ONNX text classifier (`distilbert-base-uncased-finetuned-sst`) can detect hostile, manipulative, or jailbreak-style inputs with semantic understanding — not just keyword matching. [infoq](https://www.infoq.com/articles/onnx-ai-inference-with-java/)

***

### Use Case 4 — Response Quality Scoring before Delivery

Before ArcClaw sends an answer to Telegram, an ONNX sentiment/confidence classifier can flag responses that are:
- Suspiciously short/vague (augmenting your existing `SUSPICIOUSLY_SHORT` pattern)
- Negative sentiment that shouldn't be delivered directly
- Low-confidence hallucinations to route back for retry

***

## Integration Steps

### Step 1 — Export model to ONNX (Python, one time)

```bash
pip install optimum sentence-transformers

# For embeddings
optimum-cli export onnx \
  --model sentence-transformers/all-MiniLM-L6-v2 \
  --task feature-extraction \
  ./models/embeddings/

# For intent classification (after fine-tuning on your labels)
optimum-cli export onnx \
  --model your-finetuned-intent-model \
  --task text-classification \
  ./models/intent/
```

Place resulting `.onnx` and `tokenizer.json` files in `src/main/resources/models/`.

***

### Step 2 — Add Maven dependency

```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.20.0</version>
</dependency>
<!-- Tokenizer (HuggingFace tokenizers Java binding) -->
<dependency>
    <groupId>ai.djl.huggingface</groupId>
    <artifactId>tokenizers</artifactId>
    <version>0.31.0</version>
</dependency>
```

***

### Step 3 — CDI Bean for Embeddings

```java
@ApplicationScoped
public class LocalEmbeddingModel implements EmbeddingModel {  // LangChain4J interface

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    @PostConstruct
    void init() throws Exception {
        env = OrtEnvironment.getEnvironment();
        var opts = new OrtSession.SessionOptions();
        // CPU only — add GPU execution provider here if needed
        String modelPath = getClass()
            .getResource("/models/embeddings/model.onnx").getPath();
        session = env.createSession(modelPath, opts);
        tokenizer = HuggingFaceTokenizer.newInstance(
            Path.of(getClass().getResource("/models/embeddings/tokenizer.json").toURI()));
    }

    @Override
    public Response<Embedding> embed(TextSegment segment) {
        try {
            Encoding encoding = tokenizer.encode(segment.text());
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();

            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(inputIds), new long[]{1, inputIds.length});
            OnnxTensor maskTensor = OnnxTensor.createTensor(env,
                LongBuffer.wrap(attentionMask), new long[]{1, attentionMask.length});

            var results = session.run(Map.of(
                "input_ids", inputTensor,
                "attention_mask", maskTensor));

            float[] vector = meanPool((float[][][]) results.get(0).getValue());
            return Response.from(new Embedding(vector));
        } catch (Exception e) {
            throw new RuntimeException("ONNX embedding failed", e);
        }
    }

    private float[] meanPool(float[][][] tokenEmbeddings) {
        int dims = tokenEmbeddings[0][0].length;
        float[] result = new float[dims];
        for (float[] token : tokenEmbeddings[0]) {
            for (int i = 0; i < dims; i++) result[i] += token[i];
        }
        float len = tokenEmbeddings[0].length;
        for (int i = 0; i < dims; i++) result[i] /= len;
        return result;
    }

    @PreDestroy
    void close() throws OrtException { session.close(); env.close(); }
}
```

***

### Step 4 — CDI Bean for Intent Classification

```java
@ApplicationScoped
public class OnnxIntentClassifier {

    private static final List<String> LABELS =
        List.of("coder", "coordinator", "researcher", "debugger", "architect");

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    @PostConstruct
    void init() throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(
            getClass().getResource("/models/intent/model.onnx").getPath());
        tokenizer = HuggingFaceTokenizer.newInstance(
            Path.of(getClass().getResource("/models/intent/tokenizer.json").toURI()));
    }

    public String classify(String text) throws OrtException {
        Encoding enc = tokenizer.encode(text);
        // run session, softmax logits, return LABELS.get(argmax)
        // ...same tensor construction as embedding bean above...
        float[] logits = (float[]) session.run(...).get(0).getValue();
        return LABELS.get(argmax(logits));
    }
}
```

Wire this into `IntentClassificationHandler` as a fast-path before calling the LLM classifier — fall back to LLM only if ONNX confidence score is below a threshold (e.g., `softmax[0] < 0.85`).

***

### Step 5 — Native Image Registration

```java
// Register ONNX model files and native libs for native image
// In src/main/resources/META-INF/native-image/resource-config.json:
{
  "resources": {
    "includes": [
      {"pattern": "models/.*\\.onnx"},
      {"pattern": "models/.*tokenizer.*\\.json"}
    ]
  }
}
```

The `onnxruntime` JAR bundles platform native libs (`.so`/`.dylib`/`.dll`) which are extracted at runtime to a temp dir — this works in Quarkus native image as long as the resource config includes the model files. [github](https://github.com/microsoft/onnxruntime/issues/5172)

***

## Benefit Summary for ArcClaw

| Benefit | Impact |
|---|---|
| Intent classification in ~5ms locally | Eliminates LLM token cost for routine routing |
| Embeddings without Gemini API call | RAG/memory search works fully offline |
| Vocabulary filter with semantic understanding | Catches jailbreak patterns keywords miss |
| Zero Python in production | Single binary goal fully preserved |
| LangChain4J `EmbeddingModel` interface | Drops in as a swap for existing embedding provider with no pipeline changes |

---


Perfect — clean swap it is then.

## Confirmed Approach

```
Delete SQLite data folder  →  Re-embed everything fresh  →  ONNX 768-dim going forward
```

## Best Free 768-dim ONNX Model

```bash
optimum-cli export onnx \
  --model sentence-transformers/all-mpnet-base-v2 \
  --task feature-extraction \
  ./models/embeddings/
```

`all-mpnet-base-v2` is the standard recommendation for 768-dim free embeddings — consistently outperforms Jina v2 base on most MTEB benchmarks and is ~420MB on disk. [milvus](https://milvus.io/blog/no-python-no-problem-model-inference-with-onnx-in-java-or-any-other-language.md)

## Swap Checklist for ArcClaw

**1. Export model** (one time, Python dev machine):
```bash
pip install optimum sentence-transformers
optimum-cli export onnx \
  --model sentence-transformers/all-mpnet-base-v2 \
  --task feature-extraction \
  ./models/embeddings/
```

**2. Drop files into resources:**
```
src/main/resources/models/embeddings/
├── model.onnx
├── tokenizer.json
└── tokenizer_config.json
```

**3. In `application.yaml` — disable Jina:**
```properties
# Remove or comment out:
# quarkus.langchain4j.jina.api-key=...
# quarkus.langchain4j.jina.embedding-model.model-name=...
```

**4. Wire `LocalEmbeddingModel` bean** — exactly as shown in Step 3 from the previous message. Since it implements LangChain4J's `EmbeddingModel` interface, your `IngestDocumentTool` and memory search inject it with zero pipeline changes.

**5. Delete data folder, restart** — fresh embeddings from first use.

That's the complete swap — no API key, no network call, no cost, same 768-dim vector space throughout.

---

Clean design — use Quarkus CDI with a config-driven selector. Here's the complete pattern:

## `application.properties` Toggle

```properties
# User chooses: jina | onnx
arcclaw.embedding.provider=onnx
```

***

## Interface + Two Implementations

LangChain4J's `EmbeddingModel` is already the common interface — both implementations just need a CDI qualifier to disambiguate.

**Qualifier annotation:**
```java
@Qualifier
@Retention(RUNTIME)
@Target({FIELD, METHOD, TYPE})
public @interface EmbeddingProvider {
    String value(); // "jina" or "onnx"
}
```

**Jina implementation** (existing, just add qualifier):
```java
@ApplicationScoped
@EmbeddingProvider("jina")
public class JinaEmbeddingModel implements EmbeddingModel {
    // your existing Jina wiring — unchanged
}
```

**ONNX implementation** (new):
```java
@ApplicationScoped
@EmbeddingProvider("onnx")
public class OnnxEmbeddingModel implements EmbeddingModel {
    // Step 3 bean from previous message — unchanged
}
```

***

## Selector Bean — Single Inject Point

```java
@ApplicationScoped
public class EmbeddingModelSelector {

    @ConfigProperty(name = "arcclaw.embedding.provider", defaultValue = "jina")
    String provider;

    @Inject @EmbeddingProvider("jina")
    EmbeddingModel jinaModel;

    @Inject @EmbeddingProvider("onnx")
    EmbeddingModel onnxModel;

    public EmbeddingModel get() {
        return switch (provider.toLowerCase()) {
            case "onnx" -> onnxModel;
            case "jina" -> jinaModel;
            default -> throw new IllegalStateException(
                "Unknown embedding provider: " + provider + ". Use 'jina' or 'onnx'.");
        };
    }
}
```

***

## Usage in Your Pipeline

Anywhere that currently injects `EmbeddingModel` directly, swap to the selector:

```java
// Before:
@Inject EmbeddingModel embeddingModel;

// After:
@Inject EmbeddingModelSelector embeddingSelector;

// Usage — identical call site:
embeddingSelector.get().embed(segment);
```

***

## Native Image Safety

One caveat — in native image, **both beans get compiled in** regardless of which provider is configured. That means:

- ONNX model file must always be present in `src/main/resources/models/` even if Jina is selected
- Jina API key config must exist (can be a blank placeholder) even if ONNX is selected

If binary size is a concern later, you can use `@IfBuildProperty` to conditionally exclude one:

```java
@ApplicationScoped
@EmbeddingProvider("onnx")
@IfBuildProperty(name = "arcclaw.embedding.provider", stringValue = "onnx")
public class OnnxEmbeddingModel implements EmbeddingModel { ... }
```

But for development this is unnecessary — the simple selector bean above is sufficient and gives you instant runtime switching by changing one property.