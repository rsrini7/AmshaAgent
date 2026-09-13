# Sarvam GraalPy Experiment Closeout

> [GraalPy research archive](README.md)

**Status:** Closed by maintainer decision on 2026-08-15. GraalPy is not adopted
as a generic Python-library wrapper or as the Sarvam provider implementation.

## Question and answer

The experiment asked whether Amsha could embed the Sarvam Python SDK through
GraalPy and then reuse the same approach as a general bridge to Python-only
libraries.

The scoped technical answer is:

- **Yes for the pinned Sarvam graph:** one frozen SDK/native-wheel combination
  imported and executed synchronously in a private macOS-arm64 JVM harness.
- **No as a generic Amsha abstraction:** an arbitrary Python library cannot be
  assumed compatible. Each graph brings its own wheel tags, native extensions,
  host permissions, I/O, threads, shutdown behavior, footprint, and supported
  platform matrix.
- **No adoption for Sarvam:** the Java/OpenAI-compatible `ChatModel` candidate
  satisfies the exercised synchronous contract with materially less runtime and
  operational surface.

This is a stop decision based on fitness and operational cost. It is not a claim
that GraalPy is broken or incapable of embedding compatible Python code.

## Timeline

The hands-on runtime and Java comparison occupied 2026-08-14 through
2026-08-15. Static supply-chain and toolchain preparation began on 2026-08-12
and the Maven freeze was captured on 2026-08-13.

| Date | Work completed | Result |
| --- | --- | --- |
| 2026-08-12 | SGP-01 dependency/licence/SBOM inventory and SGP-02 ABI/toolchain selection | Frozen inputs and aligned GraalPy 25.0.2 candidate established for private evaluation only |
| 2026-08-13 | Static Maven/runtime-realm freeze | Reproducible offline graph and artifact lineage captured |
| 2026-08-14 | SGP-03 JVM/VFS/native-wheel harness and SGP-04 mocked public-SDK contract corpus | Synchronous feasibility passed with `HostIO.READ` accepted limitation; async path did not close |
| 2026-08-15 | SGP-J01 native-Java comparison and LangExtract integration review | Java selected; generic-wrapper premise rejected; experiment closed |

No live Sarvam traffic, real credential, external provider request, production
source edit, reactor POM change, Native Image build, staging, or commit occurred
inside the experiment.

## Evidence ledger

The durable conclusions in this document do not depend on retaining private
third-party payloads. Detailed raw evidence remains local and ignored under
`.scratch/sarvam-graalpy-phase-0/`; consumers must verify the final hashes in
each index before relying on it.

| Slice | Evidence | Observed disposition |
| --- | --- | --- |
| SGP-01 supply chain | `offline-resolution-proof.index.json` | PASS for the frozen private graph; rights not granted |
| SGP-02 toolchain/ABI | `toolchain-abi-alignment.index.json` | Aligned 25.0.2 evaluation candidate selected |
| SGP-03 embedding/import | `sgp-03.index.json` | Technical JVM feasibility PASS with filesystem limitation |
| SGP-03 dependency realm | `maven-freeze.index.json` | Frozen runtime/build realms and provenance retained |
| SGP-04 SDK contract | `sgp-04.index.json` | 50 canonical synchronous variants PASS; async and two public-SDK features did not close |
| SGP-J01 Java comparison | `sgp-j01-native-java-chat-contract-report.md` | Java recommended for the exercised synchronous contract |
| Generic-wrapper check | `langextract-integration-options.md` | LangExtract-on-GraalPy not evaluated; its distinct graph requires a new feasibility program |

## What worked

- GraalPy embedded in the JVM and loaded the classpath-packaged Python runtime.
- `sarvamai==0.1.30`, Pydantic, and the pinned GraalPy-specific
  `pydantic-core==2.46.4` wheel imported in the approved macOS-arm64 harness.
- Ten clean offline Maven cycles, disabled-mode isolation, arbitrary-working-
  directory probes, health behavior, shutdown, provenance, licence, and
  vulnerability records passed within the private scope.
- The public synchronous SDK contract passed the implementation-neutral
  50-variant corpus for chat, tools, structured tool schemas, translation,
  authentication, errors, retry accounting, timeout, malformed responses, and
  redaction.

These results prove a specific frozen integration. They do not establish a
general Python-package compatibility promise.

## Limitations that defeated the generic-wrapper premise

1. **Native ABI is package-specific.** The SDK required a
   `graalpy312-graalpy250_312_native` `pydantic-core` wheel. A different library
   can require NumPy, pandas, regex, aiohttp, PyYAML, or other native artifacts
   with different wheel availability and process-global behavior.
2. **Filesystem isolation did not pass.** `HostIO.NONE` could not load the
   selected native payload. The experiment used `HostIO.READ`, which permits
   process-accessible host-file reads and was accepted only for the private PoC.
3. **Native code is process-trusted.** Polyglot restrictions do not contain a
   CPython-compatible native extension once loaded.
4. **Threading is not transparent.** Python/native work must use bounded Java
   platform threads with workspace-context propagation; it must not run on Java
   virtual threads. Native multi-context support is not a portable baseline.
5. **Async behavior did not close.** The frozen SDK's async initialization
   required an excluded socket pair. Bounded concurrency, cancellation,
   overload, and cross-workspace propagation therefore remained unexecuted.
6. **The runtime is materially larger.** The demonstrated GraalPy graph added
   162,130,918 runtime JAR bytes to the current Amsha graph before the
   platform-specific native wheel. The selective Java candidate added zero
   incremental runtime JAR bytes.
7. **Deployment evidence was incomplete by design.** No Native Image build,
   Linux matrix, long-running resource test, or live provider test was run.
8. **A second library starts over.** The LangExtract review found a different,
   broad mandatory graph and no GraalPy support claim. The Sarvam evidence
   cannot establish LangExtract compatibility or estimate its footprint.

## Deliberately unrun gates

The following work is classified **NOT_RUN — STOP DECISION**, not PASS or FAIL:

- SGP-05 bounded concurrency, cancellation, context propagation, and CPython
  overhead comparison;
- SGP-07 live authentication, transport, and provider-model evaluation;
- SGP-08 JVM platform matrix;
- SGP-09 Native Image platform matrix;
- SGP-10 six-cell security, lifecycle, and resource gates.

Running these gates would answer whether the GraalPy variant could be hardened,
not whether it is the simplest fit. SGP-J01 already established that the Java
path satisfies the required synchronous product contract with a smaller and
safer runtime surface, so the maintainer declined further GraalPy investment.

## Final plan

1. Use a thin Java `ChatModel` adapter over Sarvam's documented
   OpenAI-compatible HTTP contract. Keep translation as a separate typed call.
2. Reuse the SGP-04/SGP-J01 fixtures when production implementation is separately
   authorized. Do not copy or bundle the Sarvam Python SDK.
3. Keep applicable provider terms, credentials, live validation, production
   configuration, platform verification, and Native Image verification as new
   Java-provider work rather than reopening the GraalPy tickets.
4. For a future Python-only capability, define a narrow capability interface
   with serializable inputs and outputs. Prefer an isolated, supervised CPython
   sidecar. Consider GraalPy only through a new library-specific ADR and finite
   experiment when a single-process requirement is non-negotiable.

## Terminal ticket disposition

- `SGP-01` through `SGP-04` and `SGP-J01` retain their completed evidence.
- `SGP-06` records the human stop checkpoint and `SGP-11` records this final stop
  packet.
- `SGP-00A`, `SGP-00B`, `SGP-05`, and `SGP-07` through `SGP-10` are closed as
  `wontfix` for the retired Python-SDK/GraalPy path. No unresolved right is
  treated as granted; it is simply no longer awaited for that path.

The architectural decision is recorded in
[ADR-GraalPy-Integration-Boundary.md](../../adr/domain/ADR-GraalPy-Integration-Boundary.md).
