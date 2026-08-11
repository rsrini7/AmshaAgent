# Sarvam Python SDK Through GraalPy: Phase 0 Research Baseline

- Evidence status: pre-spike research; no adoption decision
- Scope: text-only, non-streaming chat first; text translation second
- Repository baseline: `1ddcf24cdd523f34287859f96e6768bd16260290`
- Evidence captured: 2026-08-11 (Asia/Kolkata)
- Target envelope supplied by the maintainer: eight simultaneous calls; macOS first,
  with macOS arm64, Linux x86-64, and Linux arm64 as the Sarvam support gates;
  Windows is only a regression/isolation gate for normal Amsha artifacts

This note records facts that Phase 0 must prove or disprove. It is not an adoption
ADR and does not authorize implementation.

## 1. Current Sarvam artifact

The official PyPI JSON API reported `sarvamai` `0.1.30`, uploaded on
2026-08-03. Both published artifacts were downloaded without importing or
executing them.

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `sarvamai-0.1.30-py3-none-any.whl` | 361,482 bytes | `ab586559e041b8dbaa03a536d6c23383f6cda86e9ab9509519408cd54ea66f09` |
| `sarvamai-0.1.30.tar.gz` | 175,653 bytes | `e53b78ea695351102e880f727715a94cca4d2c7730d9663160066b7618e964d4` |

The wheel declares `Root-Is-Purelib: true`, tag `py3-none-any`, and Python
`>=3.8,<4.0`. It contains 433 entries: 429 Python files, `py.typed`, and three
`.dist-info` files. The sdist also contains 433 entries. Neither artifact
contains a native library, test suite, `LICENSE`, `COPYING`, or `NOTICE` file.
The generated sources repeatedly identify Fern as their generator.

The mandatory direct dependencies have no upper bounds:

| Requirement in `0.1.30` | Current PyPI release on 2026-08-11 | Relevant fact |
| --- | --- | --- |
| `httpx>=0.21.2` | `0.28.1` | Pure-Python universal wheel |
| `pydantic>=1.9.2` | `2.13.4` | Selects `pydantic-core==2.46.4` |
| `pydantic-core>=2.18.2` | `2.48.0` | Rust/native extension; no universal wheel |
| `typing_extensions>=4.0.0` | `4.16.0` | Pure-Python universal wheel |
| `websockets>=12.0` | `17.0.1` | Universal wheel exists; dependency is unconditional even for text-only use |

Consequences for Phase 0:

- The `sarvamai` wheel's OS-independent tag does not make the complete
  dependency graph OS independent. Normal current resolution selects Pydantic 2
  and its native `pydantic-core`.
- An unconstrained clean build is not reproducible. Every direct and transitive
  dependency, artifact, platform marker, URL, size, and digest must be frozen
  before compatibility evidence is meaningful.
- GraalPy's own lock records exact versions, not artifact hashes. A hashed
  requirements/wheelhouse design therefore remains a blocking supply-chain
  choice if digest-enforced builds are required.

### Source and licence availability

The package metadata names `https://github.com/sarvamai/sarvam-python-sdk` as its
repository. The embedded README says Sarvam values "open-source contributions" to
the generated SDK; it does not declare the package open source. Both the GitHub
repository API and the public repository URL returned HTTP 404 on 2026-08-11. The
wheel and sdist publish generated Python source, but no public VCS history, upstream
tests, or issue history is available at the declared location.

PyPI metadata has neither `License` nor `License-Expression`, and the artifacts
contain no licence file. The README's prose is not a redistribution licence.
Bundling the SDK in a JAR or Native Image must remain blocked until a human
licence review obtains explicit redistribution terms from Sarvam or another
authoritative source.

## 2. Text-only SDK surfaces and contract drift

The inspected artifact exposes both synchronous and asynchronous clients:

- `SarvamAI.chat.completions(...)` returns a typed response for
  `stream=False` and an iterator for `stream=True`.
- `AsyncSarvamAI.chat.completions(...)` returns a typed response or async
  iterator.
- `SarvamAI.text.translate(...)` and
  `AsyncSarvamAI.text.translate(...)` expose text translation.
- The public client defaults to a 60-second HTTP timeout, sends the API key in
  `api-subscription-key`, and uses `httpx` for network I/O. Its embedded README
  documents two retries by default for 408, 429, and 5xx responses, while generated
  `_should_retry(...)` code also retries 409. Phase 0 must follow the executable code
  and record this README/code drift.

The current official API reference documents:

- `POST https://api.sarvam.ai/v1/chat/completions`, non-streaming by default,
  with `sarvam-105b` and `sarvam-105b-conversations`, a default 2,048 output-token
  limit, tools, structured output, and optional SSE streaming;
- `POST https://api.sarvam.ai/translate`, with `client.text.translate(...)`,
  `mayura:v1` and `sarvam-translate:v1`, and 22 listed target languages for the
  latter.

The API pages' sitemap timestamp was 2026-08-11T05:44:01.715Z. That is newer
than SDK `0.1.30`. Drift is already visible:

- the wheel's `SarvamModelIds` type contains only `sarvam-105b`, while the
  current API reference also lists `sarvam-105b-conversations`;
- chat docstrings in the wheel still show the obsolete example value
  `sarvam-m` even though the wheel type and current API reference do not;
- The chat reference lists bearer `Authorization` as required while also documenting
  `api-subscription-key`. The inspected synchronous SDK client constructs only the
  subscription-key header; the async client adds bearer authorization only when an
  `async_token` callback supplies it.

Therefore import success is insufficient. Authentication is a named Phase 0 gate:
mocked tests must prove the synchronous and asynchronous header modes and redaction,
then sanitized live or Sarvam-approved probes must establish which documented mode
works without patching the SDK. The exact pinned SDK, chat model, translation model,
409-inclusive retry behavior, timeout, error mapping, and response schema remain
separate contract evidence. Non-streaming chat is the primary contract; translation
must not inherit a pass from chat.

## 3. Current GraalPy embedding facts

The current first-party GraalPy embedding documentation and source were read at
Oracle's `graalpython` commit
`730597a02cd71d77e62a68876b572c356baa7695` (2026-08-06). Maven Central reported
`25.2.4` as the latest `python-embedding` and `graalpy-maven-plugin` release,
last updated 2026-07-28.

### Java and build integration

- GraalPy can be embedded on a GraalVM JDK, Oracle JDK, or OpenJDK. The current
  Maven dependency is `org.graalvm.python:python-embedding:25.2.4`; the current
  Maven plugin is `org.graalvm.python:graalpy-maven-plugin:25.2.4` with goal
  `process-graalpy-resources`.
- The plugin installs declared Python packages during the build into a managed
  virtual environment. A Virtual Filesystem deployment embeds Python sources,
  the environment, and packages into the JAR or native executable. That is the
  documented path compatible with the no-runtime-download requirement.
- Native libraries in the VFS are extracted to real files on first access.
  This is runtime filesystem extraction, not a package download. A read-only
  deployment needs a proven extraction directory or a pre-extracted external
  resource deployment.
- GraalPy 25.x implements Python 3.12.8, which satisfies the SDK's declared
  range.

The Amsha baseline is not version aligned today: JVM development uses
Temurin 25, the native tasks pin GraalVM Community `25.0.2`, Quarkus is `3.27.2`,
and current GraalPy artifacts/documentation are `25.2.4`. GraalPy components
must use a mutually compatible version set. Upgrading the native toolchain or
pinning the entire GraalPy stack to the existing `25.0.2` line is a blocking
precondition, not a detail to defer until packaging.

The official Quarkus extension catalog returned no GraalPy extension. Generic
Maven and Native Image support is evidence for a spike, not evidence that
Quarkus augmentation, resource indexing, class initialization, dev mode, or
Quarkus Native Image packaging already work.

### JVM optimization

Functionality and optimization are separate:

| JVM runtime | Official runtime-optimization status for JDK 25 |
| --- | --- |
| Oracle GraalVM 25 | Optimizing runtime supported without extra configuration |
| GraalVM Community 25 | Optimizing runtime supported without extra configuration |
| Oracle JDK/OpenJDK 25, including Temurin | Optimizing runtime only through a Polyglot Isolate; fallback interpreter otherwise |

GraalPy's performance guide says pure Python without a JIT is currently around
four times slower than CPython. This is not a prediction for the Sarvam SDK,
which is mostly network-bound, but it means a successful Temurin JVM import is
not a throughput result. The existing Amsha Temurin deployment and an optimizing
GraalVM/Polyglot-Isolate deployment require distinct measurements and an
explicit product choice.

### Native Image

Current GraalPy explicitly supports GraalVM Native Image for Java applications
that embed Python. The Maven archetype builds with `mvn -Pnative package`; the
default image embeds the Python standard library and Python packages.

The current size guidance is quantitative but generic:

- removing the pre-initialized Python heap saves about 15 MiB but slightly
  delays startup and is recommended when creating more than one context;
- disabling runtime compilation saves around 40% but can significantly reduce
  Python execution performance;
- combining documented reductions can save 50% or more.

These are not Amsha measurements. HTTPS means the Sarvam spike cannot prune TLS
support merely to meet a size target. Quarkus Native Image, the SDK, its native
extension, extraction, TLS, and a real request lifecycle must pass together.

### Native extensions and permissions

GraalPy's CPython native-extension API remains experimental. Native extensions
require `Context.Builder.allowNativeAccess(true)`. The official embedding guide
warns that native code has unrestricted system access, can bypass Truffle/JVM
security policies, has memory-management delays across the Java/native boundary,
and may retain process-global state.

This applies even though `sarvamai` itself is pure Python because current Pydantic 2
selects `pydantic-core`. PyPI publishes exact `pydantic-core==2.46.4` wheels tagged
`graalpy312-graalpy250_312_native` for Linux x86-64, Linux arm64, macOS x86-64,
and macOS arm64, but no Windows GraalPy wheel and no `graalpy252` wheel. These
25.0-tagged artifacts do not establish compatibility with the latest GraalPy
`25.2.4` embedding line. Phase 0 must choose an aligned 25.0.x stack, obtain a
25.2-compatible artifact, or prove a documented cross-install; import,
serialization, shutdown, RSS, and Native Image remain independent gates.

Official GraalPy guidance also states:

- native extensions are incompatible with Java virtual threads; every SDK
  invocation must run on a bounded platform-thread executor;
- native extensions in multiple GraalPy contexts are currently supported only
  on Linux and still require extensive testing with
  `python.IsolateNativeModules=true`;
- outside Linux, a context pool containing the same native extension cannot be
  assumed safe or supported.

### Concurrency

The blanket statement "Context is not thread-safe" is inaccurate. The Polyglot
`Context` Javadoc says a context is safe on one thread and safe across multiple
threads when accesses do not overlap. Concurrent access depends on every
initialized language; incompatible access throws `IllegalStateException`.
GraalPy implements a GIL, so two threads cannot execute Python simultaneously.

No primary source establishes that a single Sarvam/GraalPy context will sustain
eight overlapping `httpx` calls, that its network wait releases the GIL in the
required places, or that its client objects are safe under that use. Conversely,
the multi-context alternative is constrained by the native extension and is
officially Linux-only today. Phase 0 must compare bounded designs rather than
encode either assumption:

1. one context/client used by platform threads with explicit admission control;
2. one context running the async SDK and multiplexing requests on one platform
   thread;
3. a context-per-worker pool on Linux only, with native-module isolation.

The first design that meets correctness and the eight-call envelope with the
least lifecycle complexity becomes evidence; none is selected here.

## 4. Platform evidence categories

Official GraalPy test tiers are indicators, not support commitments.

| Target category | First-party baseline | Sarvam-specific blocker or unknown |
| --- | --- | --- |
| macOS arm64, JVM | GraalPy Tier 2; a GraalPy 25.0-tagged `pydantic-core` wheel exists | Selected GraalPy/native-wheel ABI alignment, Temurin fallback versus GraalVM optimization, single-context concurrency, and real TLS request remain unproved |
| macOS arm64, Native Image | GraalPy generic Native Image support; Tier 2 tuple includes GraalVM/Native Image | Quarkus build, extension extraction, RSS, shutdown, and binary overhead unproved |
| Linux x86-64, JVM | GraalPy Tier 1; a GraalPy 25.0-tagged wheel exists | Selected GraalPy/native-wheel ABI alignment, container/libc baseline, and real concurrency remain unproved |
| Linux x86-64, Native Image | GraalPy Tier 1 and generic Native Image support | Quarkus packaging and lifecycle unproved |
| Linux arm64, JVM/Native Image | GraalPy Tier 1 and a GraalPy 25.0-tagged wheel exists | Required Sarvam support gate; selected ABI alignment, JVM, and Native Image remain separate evidence rows |
| Windows x86-64, normal-Amsha JVM regression | GraalPy Tier 3; current docs warn only pure-Python binary wheels are supported | Not a Sarvam support gate. Prove normal Amsha JVM artifacts contain no GraalPy/Sarvam payload and remain unchanged |
| Windows x86-64, normal-Amsha Native Image regression | Generic GraalPy Native Image claim, but lower platform tier | Not a Sarvam support gate. Prove normal Amsha Native Image artifacts remain independent of the missing native dependency |

A pass in one row cannot close another row. JVM and Native Image results must
also remain separate within the same OS/architecture.

## 5. Reconciliation of `Ideas-Pending/GraalPy.md`

| Existing claim | Primary-source result |
| --- | --- |
| GraalPy cannot run in Native Image | False for current GraalPy. Java applications embedding Python are an explicitly documented Native Image use case. |
| GraalPy Native Image works only with pure Python; C extensions do not work | False as a blanket claim. Current docs describe native extensions in JVM and Native Image, their extraction and multi-context limitations. Compatibility of this exact Pydantic Core graph remains unproved. |
| Native Image bundles the full Python standard library by default | Substantially correct. Current docs say the archetype includes the entire standard library by default and documents manual reductions. |
| Native Image size can be reduced by roughly 20-50% | Stale/imprecise. Current guidance gives about 15 MiB for removing the pre-initialized heap, around 40% for disabling runtime compilation, and 50% or more when combining reductions; Amsha must measure its own deltas. |
| A single application-scoped context can be shared efficiently | Unproved. Lifecycle reuse is possible, but concurrency depends on language support, the GIL, client safety, and native-extension behavior. |
| `Context` is not thread-safe and must always be synchronized | Overbroad. Non-overlapping multi-thread use is documented safe; concurrent use is language-dependent. The eight-call design still requires a spike. |
| Python packages must be downloaded at runtime | False when using the documented Maven/Gradle build plugin plus VFS packaging. Package resolution occurs at build time; native libraries may still be extracted at runtime. |
| Any JDK 25 gives the same GraalPy performance | False. Current Temurin/OpenJDK uses the fallback interpreter unless Polyglot Isolates are configured; GraalVM JDK 25 has the optimizing runtime by default. |
| GraalPy package support makes the Sarvam SDK portable across all three OSes | Not established. The SDK's current native dependency has Unix GraalPy wheels but no Windows GraalPy wheel, and GraalPy's platform tiers differ. |

## 6. Evidence that remains intentionally open

Phase 0 must produce retained, reproducible records for all of the following
before an adoption ADR can be written:

- explicit Sarvam permission to redistribute `sarvamai` and a complete licence
  inventory for the frozen transitive graph;
- one GraalPy/Truffle/Native Image version line aligned with the Amsha JDK and
  Quarkus build;
- hash-locked, build-time-only dependency resolution and proof that a packaged
  JVM/native process performs no package-index access;
- import and response-model parsing for chat, then translation, without calling
  native code on a virtual thread;
- a no-transaction network boundary demonstrated by tests;
- eight simultaneous calls with bounded admission, deadlines, cancellation,
  retry accounting, deterministic overload behavior, and clean shutdown;
- three fresh start/request/shutdown cycles and a long-run RSS/native-memory
  slope record for each of the six declared Sarvam JVM/Native Image rows; normal
  Windows artifacts receive separate isolation regression evidence;
- separate JVM and Native Image artifact-size, idle RSS, peak RSS, startup-time,
  steady-state latency, throughput, and build-time deltas against the same Amsha
  baseline;
- negative startup evidence showing the optional feature is absent or inert by
  default and failures cannot affect other providers;
- a real or Sarvam-approved contract check detecting SDK/API drift without
  logging keys, prompts, completions, or raw provider responses.

## 7. Primary sources

All sources were accessed on 2026-08-11 unless otherwise stated.

### Sarvam

- [PyPI JSON for `sarvamai` 0.1.30](https://pypi.org/pypi/sarvamai/0.1.30/json)
- [PyPI project page](https://pypi.org/project/sarvamai/0.1.30/)
- [Declared SDK repository](https://github.com/sarvamai/sarvam-python-sdk)
  (HTTP 404 when checked)
- [Chat completion API](https://docs.sarvam.ai/api-reference/chat/chat-completions)
- [Chat completion guide](https://docs.sarvam.ai/api/api-guides-tutorials/chat-completion/overview)
- [Text translation API](https://docs.sarvam.ai/api-reference/text/translate-text)
- [Text translation guide](https://docs.sarvam.ai/api/api-guides-tutorials/text-processing/translation)
- [Pydantic 2.13.4 PyPI metadata](https://pypi.org/pypi/pydantic/2.13.4/json)
- [Pydantic Core 2.46.4 PyPI metadata](https://pypi.org/pypi/pydantic-core/2.46.4/json)

### GraalPy, GraalVM, and Quarkus

- [Embedding Python in Java](https://github.com/oracle/graalpython/blob/730597a02cd71d77e62a68876b572c356baa7695/docs/user/Embedding-Getting-Started.md)
- [Embedding build tools and VFS deployment](https://github.com/oracle/graalpython/blob/730597a02cd71d77e62a68876b572c356baa7695/docs/user/Embedding-Build-Tools.md)
- [Native-extension embedding limitations](https://github.com/oracle/graalpython/blob/730597a02cd71d77e62a68876b572c356baa7695/docs/user/Embedding-Native-Extensions.md)
- [Embedding permissions](https://github.com/oracle/graalpython/blob/730597a02cd71d77e62a68876b572c356baa7695/docs/user/Embedding-Permissions.md)
- [Native Images with Python](https://github.com/oracle/graalpython/blob/730597a02cd71d77e62a68876b572c356baa7695/docs/user/Native-Images-with-Python.md)
- [GraalPy platform test tiers](https://github.com/oracle/graalpython/blob/730597a02cd71d77e62a68876b572c356baa7695/docs/user/Test-Tiers.md)
- [GraalVM runtime optimization support](https://www.graalvm.org/latest/reference-manual/embed-languages/#runtime-optimization-support)
- [Polyglot `Context` thread-safety contract](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Context.html#thread-safety-heading)
- [Maven Central `python-embedding` metadata](https://repo1.maven.org/maven2/org/graalvm/python/python-embedding/maven-metadata.xml)
- [Maven Central GraalPy plugin metadata](https://repo1.maven.org/maven2/org/graalvm/python/graalpy-maven-plugin/maven-metadata.xml)
- [GraalPy Maven lock implementation](https://github.com/oracle/graalpy-extensions/blob/main/graalpy-maven-plugin/src/main/java/org/graalvm/python/maven/plugin/LockPackagesMojo.java)
- [Official Quarkus extension catalog API](https://code.quarkus.io/api/extensions)
