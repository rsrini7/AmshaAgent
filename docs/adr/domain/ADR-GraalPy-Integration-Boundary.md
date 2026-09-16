# ADR: Do not adopt GraalPy as a generic Python-library wrapper

> [GraalPy research archive](../../research/graalpy/README.md)

- **Status**: Accepted
- **Date**: 2026-08-15
- **Decision owner:** Srinivasan Ragothaman, Amsha Agent maintainer

Amsha Agent will not adopt an in-process GraalPy runtime as a generic wrapper
for arbitrary Python libraries. The private Sarvam experiment proved that a
specific, frozen Python dependency graph can run inside a JVM process, but it
also demonstrated that compatibility, permissions, native-wheel ABI, threading,
lifecycle, footprint, and platform support must be established separately for
every library graph. That is not the stable, low-cost interface implied by a
generic wrapper.

For Sarvam chat, Amsha selects the thin Java `ChatModel` path over the documented
OpenAI-compatible HTTP contract. The GraalPy/Sarvam SDK path is closed and is not
part of production, default, optional, JVM, or Native Image distributions.

## Considered options

- **Generic in-process GraalPy wrapper — rejected.** The tested SDK required a
  GraalPy-specific `pydantic-core` native wheel, process-trusted native code,
  `HostIO.READ`, platform-thread execution, a second aligned toolchain, and a
  platform-specific artifact matrix. Async and bounded concurrency were not
  established.
- **Java/OpenAI-compatible provider transport — selected for Sarvam.** It
  satisfies the exercised synchronous chat contract through Amsha's existing
  `ChatModel` seam with no incremental runtime JARs.
- **Isolated CPython sidecar — preferred only when a future Python library's
  unique behavior is a product requirement.** It keeps CPython-native packages
  outside the Amsha JVM and must sit behind a narrow, versioned JSON contract.
- **Library-specific GraalPy experiment — allowed only as a new decision.** It
  requires a hard single-process constraint and its own dependency freeze,
  permissions, lifecycle, platform, and Native Image evidence. The Sarvam spike
  cannot be reused as proof.

## Consequences

- The completed GraalPy evidence is retained as an experiment record, not an
  adopted runtime or reusable compatibility guarantee.
- SGP-05 and the unstarted GraalPy deployment-matrix work are terminally
  declined. No production dependency, reactor POM change, runtime configuration,
  or release artifact follows from this experiment.
- Sarvam SDK evaluation and redistribution permissions are not granted by this
  decision. The retired SDK path no longer waits on those permissions; any Java
  provider adoption remains subject to applicable API terms and separately
  authorized live validation.
- Python-only capabilities such as LangExtract must define a capability-level
  interface that exposes no Python or Polyglot objects. A CPython sidecar is the
  default integration candidate; GraalPy is an experiment-only alternative.

The retained two-day execution/comparison evidence and the preparatory static
work are summarized in
[Sarvam-GraalPy-Experiment-Closeout.md](../../research/graalpy/Sarvam-GraalPy-Experiment-Closeout.md).
