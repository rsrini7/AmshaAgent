# Sarvam Python SDK Through GraalPy Feasibility

## 1. Concise feasibility specification

**Status:** Phase 0 candidate; **do not adopt or implement yet**. The evidence as of
2026-08-11 supports a conditional technical spike, not a production integration.
Executable SDK work is blocked until Sarvam grants written evaluation rights, and
distribution is blocked until explicit redistribution rights exist.

The independently checked baseline is
[Sarvam-GraalPy-Phase-0-Research.md](Sarvam-GraalPy-Phase-0-Research.md). It corrects
the contradictory claims in [GraalPy.md](GraalPy.md): current GraalPy `25.2.4`
officially supports both JVM embedding and Native Image, Maven-packaged Python
resources can avoid runtime downloads, and a polyglot `Context` is not categorically
thread-unsafe. However, Quarkus plus the Sarvam dependency graph remains unproved.
Native extensions are experimental, cannot run on Java virtual threads, and require
native access.

The current PyPI release is `sarvamai==0.1.30`. Its wheel is pure Python, but its
unconditional Pydantic dependency selects native `pydantic-core`. Published
GraalPy-specific `pydantic-core` wheels cover the declared macOS and Linux
OS/architecture targets only with the GraalPy 3.12/25.0 ABI tag; no published wheel
establishes compatibility with the current GraalPy `25.2.4` embedding line, and no
Windows GraalPy wheel exists. Phase 0 must explicitly choose and prove an aligned
25.0.x stack, an upgraded 25.2.4 stack with a compatible artifact, or a documented
and tested cross-install. The SDK metadata contains no licence or licence file, and
its declared source
repository returns 404. These are release blockers, not documentation gaps that Amsha
may waive implicitly.

### Product boundary

- Primary capability: first-class, text-only, non-streaming chat through Amsha's
  existing `ChatModel` contract, including system/user/assistant messages, multi-turn
  history, tool calls, structured output, usage, errors, timeouts, and model override.
- Secondary capability: direct text-translation compatibility probe. Translation must
  not be inserted implicitly into prompts, tool arguments, or answers.
- Streaming, audio, handwritten Java REST, and an out-of-process Python service are
  outside Phase 0.
- The feature is optional, disabled by default, and absent from normal Amsha
  distributions. Sarvam-enabled JVM and Native Image artifacts are separate opt-in
  variants.
- Selecting Sarvam with a failed import or initialization fails readiness. Disabled
  mode creates no Python context or worker. Provider fallback occurs only when
  explicitly configured through an existing Amsha route.

### Execution and security boundary

- Resolve a hash-locked Python dependency graph at build time and package it through
  GraalPy's documented VFS mechanism. Runtime `pip`, package-index access, source
  builds, and package downloads are forbidden. Any extracted native library must
  resolve to a normalized absolute path that is independent of the process working
  directory; startup and request handling must also pass from an arbitrary working
  directory, as required by
  [Architecture Invariants §10.3](../docs/Architecture_Invariants.md#103-path-resolution).
- Run all Python and native-extension work on a dedicated bounded platform-thread
  executor wrapped by `WorkspaceExecutors.wrap()`/`ContextAwareExecutorService` (or
  explicitly by `WorkspaceScopeHolder.wrap()`). The provisional workload is eight
  active calls and a queue of 32. No invocation may execute on a virtual thread, and
  the active workspace/session binding must survive submission, completion,
  cancellation, timeout, and rejection paths. This follows
  [Architecture Invariants §2.7](../docs/Architecture_Invariants.md#27-asynchronous-thread-context-propagation-27-invariant).
- Never start Sarvam HTTP I/O while a transaction is active. Timeouts, cancellation,
  overload rejection, and shutdown must be explicit and bounded.
- Permit native access only in the Sarvam variant. At the Python/Truffle boundary,
  deny Java host-class access, arbitrary filesystem access, process creation, and
  package installation. These controls do not contain native code: the exact pinned
  `pydantic-core` artifact is process-trusted supply-chain code and can bypass them or
  terminate Amsha. If reviewers require containment rather than process trust, an
  independently verified OS sandbox/container becomes mandatory and the in-process
  variant is no-go until that evidence exists.
- Use only documented Sarvam SDK APIs. Boundary shims are allowed; monkey-patches,
  generated-internal dependencies, and vendored SDK modifications are no-go results.
- Do not log or trace API keys, headers, prompts, completions, translations, tool
  arguments, structured responses, or raw provider bodies.

### Deployment gates

| Sarvam-enabled target | JVM | Native Image |
| --- | --- | --- |
| macOS arm64 | Required | Required |
| Linux x86-64 | Required | Required |
| Linux arm64 | Required | Required |
| Windows | Out of current Sarvam scope | Out of current Sarvam scope |

Each cell is an independent evidence gate. A JVM result cannot close a Native Image
gate, and one OS/architecture cannot stand in for another. GraalPy must not affect
normal Amsha JVM or Native Image builds on macOS, Linux, or Windows. A missing Windows
GraalPy dependency blocks only a future Windows Sarvam variant.

Amsha currently uses Temurin 25 for JVM work, GraalVM Community `25.0.2` for native
tasks, and Quarkus `3.27.2`; the spike must select one internally consistent GraalPy,
Truffle, GraalVM, JDK 25, and Quarkus line. Measure Temurin and GraalVM JDK separately.
The Sarvam JVM variant may require GraalVM Community JDK 25 if Temurin's interpreter
path misses the performance gates.

**Feasibility conclusion:** proceed only to Phase 0 after the evaluation-rights
blocker closes. Production adoption remains no-go until every declared target and
quantitative gate passes. Only then may maintainers consider a separate adoption ADR.

## 2. Options and trade-offs

| Option | Advantages | Costs and disposition |
| --- | --- | --- |
| Opt-in GraalPy/Sarvam variant | Uses the requested SDK; retains generated request/response models; supports build-time packaging; current GraalPy can target JVM and Native Image | Adds a Python runtime, experimental native-extension boundary, native access, platform-specific artifacts, and a second runtime/toolchain matrix. **Preferred Phase 0 subject, not yet an adoption choice.** |
| Keep Sarvam unintegrated | No new licence, runtime, memory, security, or release burden | Provides no Sarvam capability. **Required default until all gates pass; final outcome if a blocker remains.** |
| One context with synchronous client and serialized admission | Simplest lifecycle and safest cross-platform native-module use | Cannot demonstrate eight simultaneous calls; useful only as a correctness and resource baseline. |
| One context with the asynchronous SDK on one dedicated context-aware platform thread | Can multiplex eight network calls without virtual threads or multiple native-module contexts while preserving workspace/session binding | Adds Python event-loop, context propagation, cancellation, Java/Python future-bridging, and shutdown complexity. **Preferred concurrency candidate if evidence passes.** |
| Context-per-worker pool | Straightforward Java-side concurrency and isolation of Python state | GraalPy native modules in multiple contexts are currently supported only on Linux; it cannot be the macOS/Linux baseline. **Reject unless future official support changes.** |
| Handwritten Java REST client or Python sidecar | Avoids some GraalPy constraints or improves process isolation | Does not answer the requested SDK-through-GraalPy question and changes the product/operations boundary. **Out of scope, not the preferred fallback.** |

No option may preserve throughput by moving network I/O into a transaction, using
virtual threads for native extensions, downloading packages at runtime, or silently
changing the provider.

## 3. Phase 0 spike tickets with blocking dependencies

These are candidate work items in this specification only; no issue or ticket has
been created.

| ID | Evidence outcome | Blocking dependencies |
| --- | --- | --- |
| `SGP-00` | Obtain written rights to download and execute the SDK for internal evaluation | Sarvam and legal/procurement response. Blocks executable SDK spikes, but not completed static inspection |
| `SGP-01` | Obtain an authoritative licence and explicit redistribution rights; clarify the unavailable declared repository and generated-artifact support policy | Sarvam and legal/procurement response. May run in parallel; blocks distributable packaging and adoption, not evaluation-only execution |
| `SGP-02` | Freeze `sarvamai`, every transitive artifact, interpreter/native ABI tags, hashes, available licences, provenance, vulnerability report, and SBOM input; resolve the graph into an offline evaluation cache | `SGP-00`; approved internal artifact repository. An unresolved redistribution inventory remains tracked under `SGP-01` |
| `SGP-03` | Select and prove one mutually compatible GraalPy/Truffle/GraalVM/JDK 25/`pydantic-core` ABI line in a local, non-distributable Quarkus JVM harness; package dependencies in VFS; prove default Amsha artifacts remain GraalPy-free and native extraction is independent of the launch directory | `SGP-02`; maintainer approval of the candidate toolchain; security approval to execute the pinned native artifact as process-trusted code or inside an approved OS sandbox |
| `SGP-04` | Import and close the SDK and exercise deterministic mocked chat/translation request, response, tool, structured-output, timeout, retry—including 408, 409, 429, and 5xx—and synchronous/async authentication-header contracts without private SDK APIs | `SGP-03`; fixed SDK/API contract and authentication fixtures |
| `SGP-05` | Against the same local deterministic HTTP fixture, compare serialized synchronous and single-context asynchronous execution under GraalPy and CPython; prove eight-call admission, bounded overload, cancellation, context-aware platform-thread execution, workspace/session propagation, transaction separation, and shutdown | `SGP-04`; async bridge design review |
| `SGP-06` | Conduct a human Phase 0A checkpoint over the local JVM/import/mock/concurrency evidence and decide whether to fund live-provider and six-cell platform work | `SGP-03` through `SGP-05`. Record `SGP-01` status, but unresolved redistribution rights do not block an evaluation-only continuation |
| `SGP-07` | Run named, sanitized live authentication-mode and SDK/transport probes for the pinned synchronous and asynchronous clients, then run a separately scored provider-model evaluation using a dedicated low-privilege credential | Human continue decision at `SGP-06`; approved subscription key and, if required, bearer-token source; quota, cost ceiling, and Sarvam availability |
| `SGP-08` | Produce separate macOS arm64, Linux x86-64, and Linux arm64 JVM records, including Temurin-versus-GraalVM measurements and disabled/fail-closed behavior; separately prove normal Windows JVM artifacts remain GraalPy-free and operational | `SGP-06`, `SGP-07`; matching CI hosts/runners and frozen artifacts |
| `SGP-09` | Produce the three corresponding Quarkus Native Image records: build, arbitrary-working-directory startup, TLS call, normalized absolute native-extension extraction/load, request, cancellation, and shutdown; separately prove the normal Windows Native Image artifact remains GraalPy-free and operational | Passing corresponding `SGP-08` row; aligned Native Image toolchain and host |
| `SGP-10` | Run Python-boundary restrictions, native supply-chain trust, lifecycle, leak, offline-runtime, sensitive-log, and resource-budget suites for every required JVM/Native Image cell | `SGP-08`, `SGP-09`; security test plan and platform telemetry |
| `SGP-11` | Assemble a signed evidence packet mapping every gate to commands, immutable inputs, environment, and retained sanitized results; issue only a continue/stop recommendation and record unresolved redistribution status as an adoption blocker | `SGP-00`, `SGP-02` through `SGP-10`; no adoption ADR or production implementation authorized |

Ticket order follows the phased pattern in
[ADR-ONNX-In-Process-Local-ML-Inference.md](ADR-ONNX-In-Process-Local-ML-Inference.md):
compatibility and supply chain precede integration; JVM and Native Image evidence
remain separate; activation is a later human decision.

## 4. Quantitative go/no-go gates

All deltas compare the Sarvam-enabled candidate with the same commit, persistence
backend, configuration, host, and build mode without Sarvam. Report medians and p95
from at least five measured runs unless a gate specifies another sample. Any failed
required cell is a no-go for adoption on the declared Sarvam matrix.

| Gate | Required result |
| --- | --- |
| Evaluation rights | Written SDK evaluation rights before any executable spike; completed static metadata inspection may be retained without satisfying this gate |
| Redistribution rights | Authoritative licence and written redistribution rights before producing a distributable artifact or considering adoption; this gate does not block evaluation-only execution |
| Artifact inventory | 100% of direct/transitive artifacts hash-pinned and present in the SBOM; all available licences and provenance recorded; zero unapproved high/critical vulnerability findings. Missing redistribution coverage remains a failed redistribution gate, not an execution gate |
| Toolchain ABI alignment | One exact GraalPy/Truffle/GraalVM/JDK 25/Python/native-wheel version set is recorded. Every `pydantic-core` wheel tag is accepted without installer override by that GraalPy line, or cross-install has authoritative compatibility documentation plus passing import/native-load evidence in every required cell; an unproved `graalpy250` wheel under GraalPy `25.2.4` is a no-go |
| Offline packaging | Ten clean builds from the frozen repository succeed; zero package-index/DNS/HTTP attempts during application startup and requests; zero runtime package installation paths |
| Native extraction path | For every required platform/deployment cell, startup/request/shutdown passes from at least three arbitrary working directories, including a read-only directory; 100% of loaded native-library paths are normalized absolute paths; zero lookup or extraction occurs relative to the working directory |
| Default isolation | Normal JVM/native artifacts contain zero `sarvamai`, GraalPy, or Python-runtime payloads; disabled Sarvam variants create zero contexts and worker threads; 100% of forced initialization failures fail readiness without affecting normal artifacts |
| Mocked protocol parity | At least 50 canonical request variants; 100% correct serialization/decoding, retry accounting, and error mapping for success, 400, 401/403, 408, 409, 429, and 5xx; 100% valid tool-call and structured-output schema handling |
| Chat authentication modes | Mocked tests prove the synchronous client sends `api-subscription-key` only, the asynchronous client always sends that key and adds bearer `Authorization` only when `async_token` supplies it, and both redact credentials. Named live probes must establish at least one documented, public-SDK-only authentication mode for each selected client path; failure is a transport no-go, not a model-quality result |
| Live SDK/transport compatibility | At least 200 fixed, non-sensitive calls using the proven authentication mode; 100% of successful provider responses decode into the documented SDK types and 100% of returned error responses map to a bounded error class. Report service success rate and malformed provider output separately; do not attribute either to GraalPy unless the deterministic fixture reproduces it |
| Provider semantic evaluation | On a separately versioned corpus of at least 200 temperature-minimized trials: at least 99% correct tool name/required arguments and 100% schema-valid structured outputs. Failure blocks Sarvam model activation, not GraalPy/SDK technical feasibility |
| Translation probe | One fixed fixture for each officially documented target language: 100% HTTP/SDK decoding success and non-empty typed text; human bilingual sampling before any later product-quality claim |
| Concurrency and overload | Exactly eight active calls; queue capacity 32; the first request beyond full capacity rejects within 100 ms; zero work on virtual threads; 100% timeout/cancellation tests finish without a late result entering agent state |
| Workspace/session propagation | 100% of accepted, completed, cancelled, timed-out, and rejected test tasks retain the submitting workspace/session binding through a context-aware platform-thread executor; concurrent requests from two workspaces show zero cross-binding or missing-binding events |
| Deterministic runtime overhead | Against direct CPython SDK execution using the same local deterministic HTTP fixture/replay server, host, payloads, and schedule, GraalPy p95 latency is no worse than `1.25x + 50 ms`, and sustained throughput is at least 80%, at concurrency one and eight |
| Live operational evidence | Report real-service p50/p95/p99 latency and status distribution separately. Evidence absence is a no-go; network/provider latency is not used as the GraalPy overhead threshold |
| Phase 0A checkpoint | `SGP-03` through `SGP-05` evidence is complete and a named human records continue or stop before `SGP-07` through `SGP-10` receive funding. Continue authorizes only the next spike phase, not adoption |
| Lifecycle | Three fresh build/start/request/shutdown cycles per required platform/deployment cell; a 60-minute/10,000-call mocked stress run has post-warm-up RSS slope no greater than 5 MiB/hour; zero leaked non-daemon threads, files, sockets, contexts, or clients; shutdown completes within 10 seconds |
| Distribution size | Compressed Sarvam-enabled artifact delta no greater than 150 MB for each JVM and Native Image distribution |
| Memory | Idle RSS delta no greater than 300 MB; peak RSS delta at eight active calls no greater than 750 MB |
| Startup and build | Readiness delta no greater than 2 seconds; Native Image build-time delta no greater than 15 minutes |
| Python-level restrictions | 100% of host-class, arbitrary-file, process-execution, and runtime-install negative tests through Python/Truffle APIs are denied. This is explicitly not evidence that native code is contained |
| Native supply-chain trust | Every loaded native artifact has an approved hash, provenance, licence status, SBOM entry, and vulnerability disposition, and is explicitly accepted as process-trusted. If reviewers require OS containment, the candidate must instead pass a separately approved sandbox/container gate |
| Security and data | Zero secret/prompt/response canary matches in logs, metrics, traces, crash output, or evidence; zero HTTP calls begun while a transaction is active |
| Platform matrix | All six required Sarvam cells—macOS arm64, Linux x86-64, and Linux arm64, each JVM and Native Image—pass independently. Separate Windows JVM and Native Image regression records show normal Amsha artifacts contain zero GraalPy/Sarvam payload and still build, start, and pass their existing smoke checks |

Averages cannot hide a failed platform, deployment mode, security check, correctness
cohort, or overhead ceiling. Any gate revision requires recorded human approval based
on retained evidence before the affected spike continues.

## 5. Open decisions requiring human approval

1. **Sarvam/legal—evaluation:** grant written rights to download and execute the SDK
   for an internal, non-distributable spike.
2. **Sarvam/legal—redistribution:** separately grant an authoritative licence and
   redistribution rights; clarify the missing source repository and generated-
   artifact support policy. This need not block evaluation-only execution.
3. **Phase 0A sponsor:** after local JVM/import/mock/concurrency evidence, record a
   continue or stop decision before funding live-provider and six-cell matrix work.
4. **Security:** choose explicitly between accepting the pinned native artifact as
   process-trusted supply-chain code and requiring an OS sandbox/container. Python
   permission tests cannot establish native containment.
5. **Release engineering:** approve the exact aligned GraalPy/GraalVM/JDK 25/Quarkus
   line, new opt-in artifact names, CI capacity, and supported host images.
6. **Runtime:** choose GraalVM Community JDK 25 or Temurin/Polyglot Isolates for the
   Sarvam JVM variant after `SGP-08`; existing Amsha runtime defaults must not change.
7. **Concurrency:** choose serialized synchronous or single-context asynchronous
   execution only after `SGP-05`. A context pool is not approvable for the current
   macOS/Linux matrix without new official native-module support.
8. **Operations/procurement:** approve the dedicated Sarvam test account, key custody,
   quota, rate limits, data-processing terms, and live-spike cost ceiling.
9. **Gate owners:** approve any proposed threshold waiver individually. No waiver may
   compensate for missing rights, secret exposure, transaction-bound I/O, virtual-
   thread native execution, runtime downloads, or a failed required platform cell.
10. **Product maintainers after Phase 0:** decide whether the retained evidence merits
   a separate adoption ADR. Streaming, implicit translation, and a Windows Sarvam
   variant each require their own future scope and evidence decision.
