# GraalPy research archive

> [!WARNING]
> Superseded for Amsha integration decisions as of 2026-08-11 and closed by the
> 2026-08-15 maintainer decision. This note contains
> contradictory and outdated claims about Native Image, native extensions, runtime
> package downloads, and `Context` concurrency. Use
> [Sarvam GraalPy Phase 0 Research](Sarvam-GraalPy-Phase-0-Research.md) and
> [SarvamAI GraalPy Feasibility](SarvamAI-GraalPy-Feasibility.md),
> [experiment closeout](Sarvam-GraalPy-Experiment-Closeout.md), and
> [accepted ADR](ADR-GraalPy-Integration-Boundary.md) as the evidence baseline;
> do not use this file to justify adoption or implementation.

## Archive contents

- [Accepted integration-boundary ADR](ADR-GraalPy-Integration-Boundary.md)
- [Experiment closeout and evidence ledger](Sarvam-GraalPy-Experiment-Closeout.md)
- [Governing feasibility record](SarvamAI-GraalPy-Feasibility.md)
- [Phase 0 research baseline](Sarvam-GraalPy-Phase-0-Research.md)
- [Java SDK options and selected direction](Sarvam-Java-SDK-Options.md)
- [Archived human-action packet](Sarvam-GraalPy-Phase-0-Human-Action-Packet.md)

GraalPy is a high-performance, embeddable Python 3 runtime built on the GraalVM platform, primarily targeting the JVM ecosystem. It allows Python code to run inside Java applications and vice versa, making it especially useful for developers like you who work deeply within the Java/Quarkus ecosystem.

## What It Is

GraalPy is an implementation of Python for the JVM, compatible with Python 3.12, and serves as both a drop-in CPython replacement and a Java-embeddable scripting engine. It is mostly written in Java and Python, and is distributed by Oracle under the GraalVM umbrella, with a fully open-source Community Edition (CE) available. [graalvm](https://www.graalvm.org/python/docs/)

## Key Capabilities

- **Embed Python in Java**: Load and run Python packages directly from Java code using the GraalVM Polyglot API - you create a `PolyglotContext` and call `eval("python", ...)` [jdriven](https://jdriven.com/blog/2025/03/Tabs-and-Brackets-Mixing-Java-and-Python-using-GraalPy)
- **Script Java with Python**: Python scripts can directly interact with Java objects, enabling powerful hybrid workflows [graalvm](https://www.graalvm.org/python/)
- **Native Binary Packaging**: Compile a Python app (with all its dependencies) into a single standalone native binary using GraalVM Native Image [docs.oracle](https://docs.oracle.com/en/graalvm/jdk/22/docs/reference-manual/python/Python-Runtime/)
- **Jython Migration Path**: GraalPy is the modern upgrade path for teams still using Jython [graalvm](https://www.graalvm.org/python/)
- **Broad Package Support**: Compatible with many AI and data science packages like NumPy and PyTorch, including experimental support for CPython's C extension API [news.ycombinator](https://news.ycombinator.com/item?id=41570708)

## Performance

GraalPy uses GraalVM's state-of-the-art JIT compiler to significantly boost execution speed. When JIT-compiled, pure Python code runs roughly **~4x faster than CPython** on official benchmarks. Some benchmarks put it even higher - around **17x faster than CPython and ~2x faster than PyPy**. However, without the JIT (e.g., on very short-lived scripts), it can be ~4x *slower* than CPython, so it shines best in long-running server processes - exactly the kind of workloads common in Quarkus/microservice architectures. [graalvm](https://www.graalvm.org/python/docs/)

## Distribution Modes

| Mode | Description | Trade-off |
|---|---|---|
| **Native** | AOT-compiled to a native executable; no JVM needed | Compact size, faster startup |
| **JVM** | Runs on JVM; full Java interop | Higher peak performance |
| **Oracle (EE)** | Enterprise edition, ~1.31x faster than CE  [news.ycombinator](https://news.ycombinator.com/item?id=41570708) | Commercial license |
| **Community (CE)** | Fully open-source | Free to use |

## Java Integration Example

GraalPy integrates cleanly via Maven Central, making it straightforward to add to a Java SE, Spring Boot, or Micronaut project: [graalvm](https://www.graalvm.org/python/)

```xml
<!-- Add to pom.xml -->
<dependency>
    <groupId>org.graalvm.python</groupId>
    <artifactId>python</artifactId>
</dependency>
```

Then embed Python directly in Java:
```java
try (Context ctx = Context.create()) {
    ctx.eval("python", "print('Hello from Python!')");
}
```

Given your work with GraalVM in the Amsha Agent project, GraalPy could be a powerful tool if you ever need to leverage Python's AI/ML ecosystem (e.g., LangChain Python libraries) directly within your Java-based agent workflows, without leaving the JVM. [graalvm](https://www.graalvm.org/python/)

---

Given Amsha Agent is a Java-based AI agent system built on Quarkus with LangChain4J, here are the most compelling GraalPy integration points:

***

## 1. Python Agent Frameworks Alongside LangChain4J

The most powerful use case: Oracle has already published a sample showing **LangGraph + GraalPy embedded into a Java microservice**. Since Amsha Agent already uses LangChain4J, GraalPy would let you run Python-native LangGraph agent graphs directly inside the same JVM process - no separate Python microservice, no gRPC, no HTTP overhead. This is especially useful for multi-agent coordination patterns that LangGraph handles well but LangChain4J hasn't fully implemented. [javapro](https://javapro.io/2026/03/10/bridging-java-and-python-for-ai-ml-in-production-the-case-for-graalpy-on-graalvm/)

***

## 2. NLP Enrichment in the Agent Pipeline

You can embed SpaCy, TextBlob, or HuggingFace Transformers via GraalPy to add NLP capabilities at specific pipeline steps: [ateam-oracle](https://www.ateam-oracle.com/want-to-use-llm-python-frameworks-in-your-java-application-try-graalpy)

- **Intent classification** before routing to the right agent sub-graph
- **Named entity extraction** from user inputs (e.g., extracting ticket IDs, file paths, branch names)
- **Sentiment scoring** on agent-generated responses before delivering to end users

These fit naturally between Amsha Agent's existing stages since they're pure in-process function calls from Java. [javapro](https://javapro.io/2026/03/10/bridging-java-and-python-for-ai-ml-in-production-the-case-for-graalpy-on-graalvm/)

***

## 3. Python-Powered RAG Document Ingestion

Amsha Agent's RAG pipeline likely ingests documents before storing embeddings in SQLite/JPA. Python's ecosystem dominates here:

- **PyMuPDF / pdfplumber** for rich PDF parsing
- **unstructured.io** for multi-format document chunking
- **Docling** (IBM's new open-source document parser) - no Java equivalent exists

The pattern: Java hands off the raw document bytes → GraalPy runs the Python parser → structured chunks return to Java for embedding via Gemini. [ateam-oracle](https://www.ateam-oracle.com/want-to-use-llm-python-frameworks-in-your-java-application-try-graalpy)

***

## 4. Hot-Loadable Python Tool Definitions

Amsha Agent's tool/action system could let operators define new agent tools in Python files at runtime, loaded via GraalPy's `polyglot.export_value` mechanism. This is a major DX win - writing a new agent capability becomes a Python script drop, not a Java compile-deploy cycle. The pattern: [jdriven](https://jdriven.com/blog/2025/03/Tabs-and-Brackets-Mixing-Java-and-Python-using-GraalPy)

```java
// Load a Python tool at runtime
Source src = Source.newBuilder("python", toolFile).build();
context.eval(src);
Value toolFn = context.getPolyglotBindings().getMember("run_tool");
Value result = toolFn.execute(inputJson);
```

***

## 5. Hybrid Model Inference

For any steps in Amsha Agent that need local model inference (rather than calling Gemini via API), GraalPy unlocks HuggingFace Transformers, sentence-transformers for embeddings, or lightweight classifiers - all running in-process. The architecture is clean: [ateam-oracle](https://www.ateam-oracle.com/want-to-use-llm-python-frameworks-in-your-java-application-try-graalpy)

| Stage | Language | Responsibility |
|---|---|---|
| Orchestration, scheduling, DB | **Java/Quarkus** | Agent lifecycle, SQLite, Telegram/Git I/O |
| LLM inference (remote) | **LangChain4J** | Gemini API calls |
| NLP / local model inference | **Python via GraalPy** | SpaCy, HuggingFace, LangGraph |
| Post-processing, routing | **Java** | Result handling, JPA persistence |

***

## Recommended Starting Point

The lowest-friction entry is **NLP enrichment at input/output boundaries** - embed a single Python function (e.g., SpaCy entity extractor) via GraalPy in one Quarkus service bean, validate the type-marshalling overhead, and expand from there. The `PolyglotContext` can be initialized as a `@ApplicationScoped` CDI bean in Quarkus for efficient reuse across requests, avoiding the cold-start cost of context creation per call. [developersummit](https://developersummit.com/session/practical-python-ai-in-java-with-graalpy)

---

Gemini's answer reads well but has **several critical omissions that directly affect Amsha Agent** - especially one that's a dealbreaker depending on your build targets.

***

## The Critical Gap Gemini Missed - Native Image Incompatibility

This is the most important thing for you specifically:

> **GraalPy does NOT work with GraalVM Native Image.**

The `PolyglotContext` that runs Python requires the full JVM JIT compiler to perform at all. Without it, GraalPy runs pure Python ~4x **slower** than CPython. Your Amsha Agent native builds via `quarkus build --native` would be completely unusable with any GraalPy integration - you'd have to ship JVM mode only for any service that embeds Python. [graalvm](https://www.graalvm.org/python/docs/)

```
Amsha Agent + GraalPy = JVM mode only
Amsha Agent native binary = no GraalPy, period
```

If you use native image anywhere in your deployment pipeline, this is a hard architectural constraint Gemini didn't mention at all.

***

## What's Genuinely Useful vs. Oversold

### ✅ Actually compelling for Amsha Agent

**Hot-loadable Python tools (Point 4)** is the most relevant one for your architecture. Your current `ToolCapability` enum requires a compile-deploy cycle for every new tool. GraalPy's `polyglot.eval` lets operators drop a `.py` file and have it available immediately - fitting naturally into your existing `ToolRegistry`. [jdriven](https://jdriven.com/blog/2025/03/Tabs-and-Brackets-Mixing-Java-and-Python-using-GraalPy)

**NLP enrichment at pipeline boundaries (Point 2)** is low-risk and high-value. A single `@ApplicationScoped` `PolyglotContext` bean injected into `IntentClassificationHandler` could replace your LLM-based intent classifier with a faster local SpaCy model for high-confidence cases. [javapro](https://javapro.io/2026/03/10/bridging-java-and-python-for-ai-ml-in-production-the-case-for-graalpy-on-graalvm/)

### ⚠️ Oversold by Gemini

**LangGraph inside JVM (Point 1)** - LangGraph has deep asyncio and Pydantic dependencies. Getting it running stably inside GraalPy inside a Quarkus thread model is a significant undertaking. The Oracle sample Gemini references is a toy demo, not production-validated. For Amsha Agent's multi-agent coordination, your existing Java state machine is already doing what LangGraph would do.

**HuggingFace Transformers (Point 5)** - Most HuggingFace packages use C extensions (native `.so` files). GraalPy's support for C extension packages is still **experimental and incomplete**. `sentence-transformers` specifically would be very difficult to run reliably. [graalvm](https://www.graalvm.org/python/docs/)

***

## Practical Starting Point for Amsha Agent

Gemini's recommendation to start with NLP enrichment is correct, but here's the concrete wiring for your architecture:

```java
@ApplicationScoped
public class GraalPyRuntime {

    private final Context polyglotCtx;

    @PostConstruct
    void init() {
        polyglotCtx = Context.newBuilder("python")
            .allowAllAccess(true)
            .build();
        // warm up imports once at startup
        polyglotCtx.eval("python", "import spacy; nlp = spacy.load('en_core_web_sm')");
    }

    public synchronized String extractEntities(String text) {
        polyglotCtx.getBindings("python").putMember("_input", text);
        return polyglotCtx.eval("python",
            "str([(ent.text, ent.label_) for ent in nlp(_input).ents])")
            .asString();
    }

    @PreDestroy
    void close() { polyglotCtx.close(); }
}
```

**One important note:** `Context` is **not thread-safe** - a single `@ApplicationScoped` bean needs `synchronized` methods or a pool pattern, since Quarkus handles requests on multiple threads. Gemini did not mention this.

***

## Summary Verdict

| Gemini Point | Verdict |
|---|---|
| LangGraph inside JVM | Technically possible, practically risky |
| NLP enrichment | ✅ Best starting point - valid |
| RAG document parsing | ✅ Valid, Python tooling is genuinely better here |
| Hot-loadable Python tools | ✅ Very relevant to Amsha Agent's ToolRegistry pattern |
| HuggingFace Transformers | ⚠️ C extensions not reliably supported |
| Native Image compatibility | ❌ Not mentioned - critical omission for your setup |
| Thread safety of Context | ❌ Not mentioned - will cause bugs in Quarkus |

---

First, I owe you a correction from my earlier message - I was **wrong** about GraalPy and Native Image.

***

## Correction First

> ~~"GraalPy does NOT work with GraalVM Native Image"~~

GraalPy **does** support Native Image via `mvn -Pnative package`. I gave you incorrect information earlier - apologies. It uses the Truffle framework which has AOT support built in. [graalvm](https://www.graalvm.org/jdk23/reference-manual/python/native-applications/index.html)

***

## But Here's the Real Concern - Binary Size

GraalPy native image support comes with a **significant cost** that does matter for Amsha Agent: [graalvm](https://www.graalvm.org/jdk23/reference-manual/python/native-applications/index.html)

- Including GraalPy bundles the **entire Python standard library** into the binary by default
- Even with all the pruning flags (`WithoutSSL`, `WithoutDigest`, `WithoutCompressionLibraries`, etc.), size only reduces **~20-50%**
- A typical Quarkus native binary is ~50-80MB. GraalPy adds a substantial chunk on top
- C extension packages (SpaCy, NumPy, HuggingFace) still **do not work** in native image - only pure Python

So **single binary is possible**, but bloated and limited to pure-Python only.

***

## Verdict for Amsha Agent - Stop Here

Given Amsha Agent's priorities:

| Factor | Reality |
|---|---|
| Single binary goal | Possible but binary grows significantly |
| SpaCy / HuggingFace | ❌ C extensions blocked in native image |
| Hot-loadable tools | ❌ Python files must be bundled at build time, not dropped at runtime |
| LangGraph | ❌ asyncio + C deps - won't work in native |
| Simplicity | ❌ Complex build configuration for marginal gain |

The two use cases that made GraalPy interesting for Amsha Agent were **hot-loadable Python tools** and **NLP via SpaCy** - both break in native image exactly where you need them.

***

## Native-Image-Compatible Alternative

**ONNX Runtime for Java** is the clean answer. Python is used **once** to export a model to `.onnx` - then it runs entirely in Java with zero Python runtime, zero binary size penalty, and full native image compatibility. [the-main-thread](https://www.the-main-thread.com/p/quarkus-onnx-pgvector-image-search-tutorial)

```
Python (dev time only)          Java/Quarkus (production)
─────────────────────           ─────────────────────────────
Train/export model       →      Load .onnx via ONNX Runtime Java
spacy → model.onnx              InferenceSession.run(input)
sentence-transformers.onnx      EmbeddingModel wrapping ONNX
```

For Amsha Agent specifically:
- **Intent classification** → export a small classifier to ONNX, run in-process, replaces LLM-based routing for obvious cases
- **Embeddings for RAG** → `sentence-transformers` exports cleanly to ONNX, runs via `ai.onnxruntime:onnxruntime` Maven dependency
- **Single binary, no Python anywhere near production** ✅

GraalPy is not worth pursuing for Amsha Agent given your native binary requirement. ONNX is the correct path if local inference matters to you.
