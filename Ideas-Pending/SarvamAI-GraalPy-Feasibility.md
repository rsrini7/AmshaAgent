# Sarvam Python SDK Through GraalPy Feasibility

## 1. Concise feasibility specification

**Status:** Phase 0A private exception effective 2026-08-12. SGP-00A remains
`needs-info`, unresolved, and unpassed while the authoritative evaluation-rights
response is pending. No adoption, production implementation, redistribution, or
customer use is authorized. SGP-00B also remains unresolved and continues to
block redistribution and adoption. The exception below is a separate, narrowly
scoped project-risk authorization for local evidence; it is not a Sarvam licence
grant and does not waive or pass either rights gate.

### Maintainer-authorized private Phase 0A feasibility exception

For Phase 0A only, Srinivasan Ragothaman, maintainer of Amsha Agent, accepts
the project risk of privately executing `sarvamai==0.1.30` and its pinned
`pydantic-core` native wheel as process-trusted native code. The acceptance was
recorded on 2026-08-12 and is effective only for the ordered local path
`SGP-03` → `SGP-04` → `SGP-05`, initially on a private, non-production,
non-distributable macOS-arm64 Quarkus JVM harness. Srinivasan Ragothaman is the
named maintainer and native-code security risk owner when no separate
organizational security reviewer exists; no legal, procurement, or security
approver is inferred.

Only the resolved SGP-02 aligned-25.0.2 stack and hash-pinned artifacts in the
approved cache may be used. SGP-03 must begin with a static Maven freeze stage;
that stage is a hard pre-import gate, not a ticket blocker. It may perform a
one-time static acquisition of POM/JAR artifacts only, using exact allowlisted
coordinates from the allowlisted Maven Central repository. The allowlist must
cover every transitive GraalPy, Polyglot, Truffle, runtime, and build-plugin
artifact required by the aligned stack. Acquisition must not execute Maven
plugins, Java classes, Python, SDK code, or native libraries, and must record
hashes, provenance, licences, SBOM inputs, and vulnerability disposition. All
subsequent builds and imports must use the frozen Maven and Python caches
offline. Runtime package installation, package-index access, DNS, external HTTP,
Sarvam endpoints, credentials, and live provider calls are forbidden. SGP-04
may use loopback HTTP only for its deterministic local fixture; SGP-05 uses the
same fixture. Maven/build orchestration may create required build processes,
but the running harness must deny child-process creation.

All Python execution—including SDK import, calls, lifecycle, and native
extensions—must run on a dedicated bounded platform-thread executor wrapped by
`WorkspaceExecutors.wrap()`/`ContextAwareExecutorService` (or explicitly by
`WorkspaceScopeHolder.wrap()`). Workspace/session context must survive
submission, completion, cancellation, timeout, rejection, and shutdown. Java
virtual threads must not execute Python or native-extension work. Never start
Sarvam HTTP I/O while a transaction is active.

At the Python boundary, deny Java host-class access, filesystem access where
the GraalPy VFS policy supports it, runtime installation, and child processes.
These controls do not contain process-trusted `pydantic-core` native code.
For SGP-03, SGP-04, and SGP-05 only, the 2026-08-14 maintainer decision
extends the private Phase 0A path to accept GraalPy
`VirtualFileSystem.HostIO.READ` as an `ACCEPTED_LIMITATION`: the minimum
mode that loads the pinned classpath VFS/native payload, while permitting
process-accessible host-file reads. This is never a filesystem-security PASS.
The failing `HostIO.NONE` import probe and permissive `HostIO.READ` probe
remain retained evidence. The exception is private, local, non-production,
and non-distributable; it requires frozen hash-verified inputs, a dedicated
low-privilege sanitized environment, synthetic non-secret fixture credentials
only, loopback traffic only, and no external HTTP/DNS, Sarvam/provider calls,
real credentials, or untrusted inputs. It expires at the SGP-06 human
checkpoint, does not pass SGP-00A or SGP-00B, and is not a licence grant,
adoption approval, or deployable-variant approval. Process-trusted
`pydantic-core` remains outside Polyglot containment. Any future deployable
variant requires separately approved OS-level containment or a newer
documented GraalPy capability.

Stop Phase 0A if SDK modification, vendoring, private/generated SDK APIs,
external traffic, credentials, unpinned or mismatched artifacts, unexpected
native-library paths, virtual-thread Python/native execution, or unsanitized
retained evidence becomes necessary. SGP-00B remains an independent
redistribution and adoption blocker.

### Phase 0A HostIO.READ limitation (SGP-03–SGP-05)

On 2026-08-14, Srinivasan Ragothaman accepted `HostIO.READ` as an
`ACCEPTED_LIMITATION` for SGP-03 through SGP-05 in the private local PoC.
This named project-risk acceptance is not a licence grant, rights-gate pass,
filesystem-security pass, adoption approval, or deployable-variant approval.
It requires frozen hash-verified inputs, a dedicated low-privilege sanitized
environment, synthetic non-secret fixture credentials only, loopback traffic
only, no external HTTP/DNS, Sarvam/provider calls, real credentials,
untrusted inputs, or redistribution, and expires at the SGP-06 human
checkpoint. Production, customer use, adoption, and Native Image work remain
unauthorized. The HostIO.NONE failure and permissive HostIO.READ evidence and
the rejected multi-context-native option remain preserved. Any future
deployable variant requires separately approved OS-level containment or a
newer documented GraalPy capability.

### Open-source publication boundary

Only original Amsha harness, integration, test, and documentation source may be
published. Upstream artifacts, caches, wheels, SDK/generated sources,
SDK-containing VFS payloads, evidence containing upstream source snapshots, and
built Sarvam-enabled runtime artifacts are prohibited from publication or
redistribution.

Proposed `LICENSE`, `README`, and `THIRD-PARTY-NOTICES` wording:

> The project licence covers only original Amsha Agent code. The Sarvam SDK is
> not bundled or sublicensed; users acquire it separately and remain
> responsible for its terms. Its unresolved licence remains a release and
> adoption blocker.

The independently checked baseline is
[Sarvam-GraalPy-Phase-0-Research.md](Sarvam-GraalPy-Phase-0-Research.md). It
corrects contradictory claims in [GraalPy.md](GraalPy.md): current GraalPy
25.2.4 supports JVM embedding and Native Image, Maven-packaged Python
resources can avoid runtime downloads, and Polyglot `Context` is not
categorically thread-unsafe. The Quarkus/Sarvam graph and native-extension
deployment remain evidence questions. The available `pydantic-core` native
wheels carry the GraalPy 25.0 ABI line; SGP-02 selected the aligned 25.0.2
candidate rather than claiming 25.2.4 compatibility.

### Product boundary

- Primary capability: first-class, text-only, non-streaming chat through
  Amsha's existing `ChatModel` contract, including system/user/assistant
  messages, multi-turn history, tool calls, structured output, usage, errors,
  timeouts, and model override.
- Secondary capability: direct text-translation compatibility probe. Translation
  is not inserted implicitly into prompts, tool arguments, or answers.
- Streaming, audio, handwritten Java REST, and an out-of-process Python service
  are outside Phase 0.
- The feature is optional and disabled by default; normal Amsha distributions
  remain GraalPy/Sarvam-free. Sarvam-enabled JVM and Native Image artifacts,
  if ever approved, are separate opt-in variants.
- Failed selected-mode initialization fails readiness. Disabled mode creates no
  Python context or worker; fallback occurs only through an explicitly
  configured existing Amsha route.

### Deployment and security gates

| Sarvam-enabled target | JVM | Native Image |
| --- | --- | --- |
| macOS arm64 | Required | Required |
| Linux x86-64 | Required | Required |
| Linux arm64 | Required | Required |
| Windows | Out of current Sarvam scope | Out of current Sarvam scope |

Each Sarvam cell is an independent gate. JVM evidence cannot close Native
Image evidence, and one OS/architecture cannot stand in for another. Windows
is only a regression/isolation gate for normal Amsha artifacts; it is not a
Sarvam support gate.

The baseline uses Temurin 25 for JVM work, GraalVM Community 25.0.2 for native
tasks, and Quarkus 3.27.2. The spike must record one internally consistent
GraalPy/Truffle/GraalVM/JDK 25/Quarkus line, with Temurin and GraalVM JDK
implications measured separately.

**Feasibility conclusion:** SGP-00A remains unresolved and unpassed. The
effective private exception authorizes only the local `SGP-03` → `SGP-04` →
`SGP-05` evidence path. SGP-07 additionally requires SGP-00A to pass with
explicit written live-test permission, as well as its human checkpoint,
credential, quota, cost, and availability controls. SGP-00B still blocks
distributable packaging and adoption. Only after the declared evidence gates
pass may maintainers consider a separate adoption ADR.

## 2. Options and trade-offs

| Option | Advantages | Costs and disposition |
| --- | --- | --- |
| Opt-in GraalPy/Sarvam variant | Uses the requested SDK and generated models; supports build-time VFS packaging; can target JVM and Native Image | Adds a Python runtime, experimental native-extension boundary, native access, platform-specific artifacts, and a second toolchain matrix. Preferred Phase 0 subject, not an adoption decision. |
| Keep Sarvam unintegrated | No new licence, runtime, memory, security, or release burden | Provides no Sarvam capability. Required default until every adoption gate passes. |
| One context with serialized synchronous execution | Simplest lifecycle and safest native-module baseline | Does not demonstrate eight simultaneous calls; useful correctness/resource baseline. |
| One context with asynchronous SDK on one context-aware platform thread | May multiplex calls without virtual threads while preserving workspace/session binding | Adds event-loop, cancellation, future-bridging, and shutdown complexity; candidate only after SGP-05 evidence. |
| Context-per-worker pool | Straightforward Java-side isolation | Native modules in multiple contexts are not an approved macOS baseline; reject unless authoritative support changes. |
| Handwritten Java REST client or Python sidecar | Could reduce some runtime constraints | Does not answer the requested SDK-through-GraalPy question and is outside this Phase 0 scope. |

No option may move network I/O into a transaction, execute Python/native code on
virtual threads, download packages at runtime, or silently change provider.

## 3. Phase 0 spike tickets

These rows map the local tickets under
`.scratch/sarvam-graalpy-phase-0/issues/`; the identifiers below are the
governing identifiers and current actionable states.

| ID | Evidence outcome | Blocking dependencies |
| --- | --- | --- |
| `SGP-00A` | Secure Sarvam SDK evaluation rights, including explicit SGP-07 live-test permission | `needs-info`; authoritative written evaluation and live-test response remains pending |
| `SGP-00B` | Resolve licence redistribution/adoption rights | `needs-info`; redistribution response independently blocks packaging and adoption |
| `SGP-01` | Freeze SDK dependency graph, hashes, SBOM inputs, provenance/licence inventory, vulnerabilities, and prove offline resolution | `resolved`; private-PoC exception covered static work only; SGP-00A/00B remain unresolved |
| `SGP-02` | Select and approve one compatible GraalPy/Truffle/GraalVM/JDK 25/`pydantic-core` ABI line | `resolved`; exact aligned-25.0.2 candidate approved for Phase 0 evaluation only |
| `SGP-03` | Build an isolated, reusable macOS-arm64 Quarkus JVM VFS PoC harness; import and close the public SDK; load process-trusted `pydantic-core`; prove default-artifact isolation and native extraction paths | `resolved`; human acceptance 2026-08-14; evidence `.scratch/sarvam-graalpy-phase-0/evidence/sgp-03/sgp-03.index.json`; SGP-00A/SGP-00B remain unresolved. |
| `SGP-04` | Deterministic SDK contract evidence: sync chat/translation, serialization, decoding, errors, retries, authentication headers, tools, and structured tool schemas | `resolved`; human acceptance 2026-08-14; evidence `.scratch/sarvam-graalpy-phase-0/evidence/sgp-04/sgp-04.index.json`; sync contract PASS; async execution is NO-GO under the approved no-socket policy; `async_token` and native `response_format` are `UNSUPPORTED_BY_PINNED_PUBLIC_SDK`; tool-schema structured output PASS; original eight-active GraalPy concurrency target is not satisfied. |
| `SGP-J01` | Compare the implementation-neutral SGP-04 corpus with Amsha's native Java/LangChain4j ChatModel path and recommend Java, GraalPy, or additional evidence | `ready-for-agent`; SGP-04 resolved |
| `SGP-05` | Compare serialized synchronous and single-context asynchronous execution; prove bounded concurrency, cancellation, context propagation, transaction separation, and shutdown | `needs-triage`; SGP-J01 comparison must complete and a human must decide that GraalPy remains a contender before SGP-05 or its async-bridge design begins |
| `SGP-06` | Human Phase 0A checkpoint over SGP-03–05 evidence before any live-provider platform work | `needs-triage`; SGP-03 through SGP-05; record SGP-00B separately |
| `SGP-07` | Named, sanitized live authentication/transport probes and separately scored provider-model evaluation | `needs-triage`; requires a future SGP-00A pass with explicit written live-test permission, SGP-06, credentials/quota/cost approval, and Sarvam availability |
| `SGP-08` | Separate JVM records for macOS arm64, Linux x86-64, and Linux arm64; Windows normal-artifact isolation regression | `needs-triage`; SGP-06/07, matching runners, and frozen artifacts |
| `SGP-09` | Independent Native Image records for the three Sarvam targets; Windows normal-artifact isolation regression | `needs-triage`; corresponding SGP-08 records and aligned Native Image host |
| `SGP-10` | Security, lifecycle, offline-runtime, sensitive-log, and resource gates for every required JVM/Native Image cell | `needs-triage`; SGP-08/09, approved security plan, and telemetry |
| `SGP-11` | Final signed evidence packet and continue/stop recommendation; unresolved redistribution remains an adoption blocker | `needs-triage`; SGP-00A and SGP-01 through SGP-10; record SGP-00B separately |

The order follows the phased evidence-gate pattern in
[ADR-ONNX-In-Process-Local-ML-Inference.md](ADR-ONNX-In-Process-Local-ML-Inference.md):
supply chain and compatibility precede integration; JVM and Native Image
evidence remain separate; activation is a later human decision.

### SGP-04 terminal dispositions

The accepted SGP-04 result records the public synchronous contract as `PASS`, async
execution under the approved no-socket policy as `NO-GO`, `async_token` authentication
as `UNSUPPORTED_BY_PINNED_PUBLIC_SDK`, and the native `response_format` parameter as
`UNSUPPORTED_BY_PINNED_PUBLIC_SDK`. Tool-schema structured output is `PASS`. These
terminal dispositions do not claim async parity and do not satisfy the original
eight-active GraalPy concurrency target. SGP-J01 must complete before SGP-05 can be
considered for execution; a human must first decide that GraalPy remains a contender.

## 4. Quantitative go/no-go gates

Unless a gate says otherwise, compare the Sarvam-enabled candidate with the
same commit, persistence backend, configuration, host, and build mode without
Sarvam; report medians and p95 over at least five runs. A failed required cell
is a no-go for adoption on the declared Sarvam matrix.

| Gate | Required result |
| --- | --- |
| Evaluation rights | SGP-00A remains unresolved and unpassed. The private project-risk exception authorizes only SGP-03–05; it does not satisfy this gate. SGP-07 requires authoritative written permission explicitly covering its live authentication, transport, and model tests, plus its other blockers. SGP-00B remains separate. |
| Redistribution rights | Written authoritative redistribution rights are required before any distributable artifact or adoption; this gate does not block evaluation-only execution. |
| Artifact inventory | Every direct/transitive artifact is hash-pinned and in the SBOM; licences/provenance and vulnerability disposition are recorded; no unapproved high/critical finding. |
| Maven/Python offline packaging | Hash-pinned Maven and Python graphs resolve from frozen caches; ten clean builds succeed; zero package-index, DNS, or external HTTP attempts and zero runtime installation paths. |
| Toolchain ABI | Exact GraalPy/Truffle/GraalVM/JDK 25/Python/native-wheel versions are recorded; all wheel tags pass normal installer compatibility; no unproved 25.2.4/native-wheel cross-install is accepted. |
| Native extraction | Each required cell passes startup/request/shutdown from at least three arbitrary working directories, including read-only; every loaded native path is normalized absolute and independent of launch directory. |
| Default isolation | Normal JVM/Native Image artifacts contain no Sarvam/GraalPy/Python payload; disabled mode creates no context/worker; forced selected-mode failure affects only readiness. |
| Mocked protocol parity | At least 50 canonical variants pass sync serialization/decoding, bounded error mapping, retry accounting for 400, 401/403, 408, 409, 429, and 5xx, plus tool/structured-output schemas; async execution is a terminal no-go under the approved no-socket policy. |
| Authentication modes | Sync `api-subscription-key` and redaction pass; async bearer/`async_token` behavior is `UNSUPPORTED_BY_PINNED_PUBLIC_SDK`; native `response_format` is `UNSUPPORTED_BY_PINNED_PUBLIC_SDK`; any later live probe uses a documented public SDK mode and is scored separately from model quality. |
| Concurrency/context | Exactly eight active calls, queue 32, bounded overload rejection, zero Python/native work on virtual threads, and 100% workspace/session binding through all task outcomes. |
| Deterministic overhead | Against the same local deterministic fixture/replay server and CPython baseline, GraalPy p95 is no worse than `1.25x + 50 ms` and sustained throughput is at least 80% at concurrency one and eight. Real-service latency is reported separately. |
| Phase 0A checkpoint | Named human continue/stop decision after SGP-03–05 and before SGP-07–10 funding; continue authorizes only the next spike phase. |
| Lifecycle/resources | Three fresh build/start/request/shutdown cycles per cell; 60-minute/10,000-call mocked stress has RSS slope ≤5 MiB/hour, no leaked non-daemon resources, and shutdown ≤10 seconds; idle RSS delta ≤300 MB and peak delta ≤750 MB. |
| Python/native security | Python-level host/file/process/install denials pass where supported; SGP-03–05 `HostIO.READ` is an `ACCEPTED_LIMITATION`, not a filesystem-isolation PASS. Native code remains process-trusted; deployable variants require separately approved OS containment or newer documented GraalPy capability; zero secret/prompt/response leakage and no external or transaction-bound HTTP. |
| Platform matrix | macOS arm64, Linux x86-64, and Linux arm64 each pass independently on JVM and Native Image. Windows records only normal-Amsha regression/isolation. |

No average may hide a failed platform, deployment mode, security check,
correctness cohort, or overhead ceiling. Any threshold revision requires
recorded human approval before the affected spike continues.

The generic Python/native-security row above is superseded for SGP-03–05 by the
named `HostIO.READ` `ACCEPTED_LIMITATION`: filesystem isolation is not PASS,
the `HostIO.NONE` failure and permissive `HostIO.READ` result remain evidence,
and any deployable variant requires separately approved OS-level containment or
a newer documented GraalPy capability. This private exception does not pass
SGP-00A or SGP-00B and does not authorize live provider calls, production,
customer use, adoption, redistribution, Native Image work, or SGP-06+.

## 5. Open decisions requiring human approval

1. **Sarvam/legal — evaluation:** obtain written rights to download and execute
   the SDK for an internal, private, non-distributable spike.
2. **Sarvam/legal — redistribution:** obtain authoritative redistribution
   rights and clarify the generated-artifact/source-repository policy. This is
   independent of evaluation and remains an adoption blocker.
3. **Phase 0A sponsor:** after SGP-03–05, record continue/stop before funding
   live-provider and six-cell platform work.
4. **Security:** retain the pinned native wheel as process-trusted code (the
   current named owner is Srinivasan Ragothaman) or require an approved OS
   sandbox/container. Python permission tests cannot establish native
   containment.
5. **Release engineering:** approve the aligned 25.0.2 GraalPy/GraalVM/JDK 25/
   Quarkus line, opt-in artifact names, CI capacity, and host images.
6. **Runtime:** choose GraalVM Community JDK 25 or Temurin/Polyglot Isolates for
   any later Sarvam JVM variant; existing Amsha defaults must not change.
7. **Concurrency:** choose serialized synchronous or single-context
   asynchronous execution only after SGP-05; a context pool is not approved on
   the current macOS/Linux matrix without new authoritative native-module
   support.
8. **Operations/procurement:** approve any future test account, key custody,
   quota, rate limits, data-processing terms, and live-spike cost ceiling.
9. **Gate owners:** approve threshold changes individually. No waiver may
   satisfy evaluation or redistribution rights, permit secret exposure,
   transaction-bound I/O, virtual-thread Python/native execution, runtime
   downloads, or a failed required platform cell. This release/adoption rule
   does not revoke the separately recorded Phase 0A private project-risk
   authorization for its limited local evidence path.
10. **Product maintainers:** decide after Phase 0 whether retained evidence
    merits a separate adoption ADR. Streaming, implicit translation, and a
    Windows Sarvam variant require their own future evidence decisions.
