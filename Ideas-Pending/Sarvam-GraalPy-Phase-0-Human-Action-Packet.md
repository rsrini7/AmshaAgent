# Sarvam Python SDK Through GraalPy: Phase 0 Human-Action Packet

**Status:** Draft for human review only. No request has been sent, no credential or artifact has been accessed, no ticket has been edited, and neither rights gate is complete.

This packet supports [SGP-00A](../.scratch/sarvam-graalpy-phase-0/issues/01-sgp-00a-evaluation-rights.md) and [SGP-00B](../.scratch/sarvam-graalpy-phase-0/issues/02-sgp-00b-redistribution-rights.md). The [feasibility specification](SarvamAI-GraalPy-Feasibility.md) governs; the [Phase 0 research baseline](Sarvam-GraalPy-Phase-0-Research.md) supplies the artifact and platform evidence. Neither document authorizes adoption or implementation.

### Local ticket to governing-plan map

| Local ticket | Governing plan mapping |
| --- | --- |
| `SGP-00A` | `SGP-00` — evaluation rights |
| `SGP-00B` | `SGP-01` — redistribution rights |
| `SGP-01` | `SGP-02` — dependency-graph freeze |
| `SGP-02` | `SGP-03` — ABI/toolchain compatibility split |
| `SGP-03` | `SGP-03` — Quarkus JVM/VFS harness split |
| `SGP-04`–`SGP-11` | `SGP-04`–`SGP-11` — same downstream plan identifiers |

The local identifiers in this packet and the governing-plan identifiers are not interchangeable.

### Retained evidence and crash-handling boundary

For this packet, **provider data** means API keys or tokens, request headers, prompts/messages, tool arguments, completions/translations, structured responses, raw provider bodies, and any sensitive test content. Retained Phase 0 evidence is limited to content-free operational metadata (versions, hashes, platform/toolchain, timestamps, counts, durations, status/error classes, sizes, exit codes, and pseudonymous run identifiers) plus logs sanitized to remove provider data and secrets. Raw requests and responses are never retained. Heap and core dumps are disabled for live-provider tests; an unavoidable raw dump is not evidence and must be quarantined and destroyed under the approved incident process after any necessary metadata extraction.

## 1. Fixed proposal under review

| Item | Proposed scope |
| --- | --- |
| SDK under evaluation | `sarvamai==0.1.30` from PyPI. Preferred artifact: `sarvamai-0.1.30-py3-none-any.whl`, SHA-256 `ab586559e041b8dbaa03a536d6c23383f6cda86e9ab9509519408cd54ea66f09`. The published sdist is `sarvamai-0.1.30.tar.gz`, SHA-256 `e53b78ea695351102e880f727715a94cca4d2c7730d9663160066b7618e964d4`; it is not required for execution unless the approved evidence plan says so. |
| Dependency boundary | The SDK wheel is pure Python, but its unconstrained graph selects native `pydantic-core`. The complete graph, artifact hashes, licenses, provenance, SBOM entries, and vulnerability disposition are not yet frozen. Local ticket [SGP-01](../.scratch/sarvam-graalpy-phase-0/issues/03-sgp-01-freeze-dependency-graph.md) (plan mapping SGP-02) must resolve that graph into an approved offline cache after SGP-00A passes; SGP-00B does not perform dependency resolution. |
| Embedding candidate | GraalPy `org.graalvm.python:python-embedding:25.2.4` with `org.graalvm.python:graalpy-maven-plugin:25.2.4` and build-time `process-graalpy-resources`/VFS packaging. The current `pydantic-core` GraalPy wheels carry `graalpy312-graalpy250_312_native` tags, not `graalpy252`; ABI/toolchain alignment remains an explicit later gate. |
| Application/runtime envelope | Quarkus `3.27.2`, JDK 25, JVM and Native Image as separate evidence gates. The existing GraalVM Community `25.0.2` Native Image pin and the GraalPy `25.2.4` candidate must be reconciled before compatibility work. |
| Capability | Text-only, non-streaming chat first through the existing `ChatModel` boundary, including multi-turn history, system/user/assistant messages, tool calls, structured output, usage, bounded errors/timeouts, and model override. Text translation is a separate direct SDK compatibility probe second; it does not inherit chat or insert implicit prompts, tool arguments, or answers. No audio, streaming, or broader provider/product scope is requested. |
| Sarvam platform gates | macOS arm64, Linux x86-64, and Linux arm64, each independently for JVM and Native Image. Windows has separate normal-Amsha JVM and Native Image regression/isolation records; it is not a Sarvam support gate. A normal-artifact regression failure blocks safe activation/release, but does not turn Windows into a Sarvam GraalPy support gate. |
| Internal test envelope | Eight simultaneous calls, bounded queue capacity 32, deterministic local HTTP fixture before live-provider work, and separately scored model-quality evaluation. Synchronous and asynchronous SDK authentication behavior must be tested independently. |
| Safety and security | Optional variant disabled by default; normal artifacts contain no Sarvam/GraalPy payload. Build-time/offline packaging only; no runtime package downloads or index access; no network I/O inside transactions (including Sarvam HTTP); no native-extension work on Java virtual threads. Python-level host/file/process/install restrictions are required, but they do not contain native code. Security must explicitly accept the pinned native artifact as process-trusted supply-chain code or require an independently verified OS sandbox/container. |
| Executor/context boundary | All Python/native work runs on a bounded platform-thread executor wrapped with `WorkspaceExecutors.wrap()`/`ContextAwareExecutorService` (or the approved `WorkspaceScopeHolder.wrap()` equivalent), preserving workspace/session context on submit, completion, cancellation, timeout, and rejection paths. |

The package pin above is the exact SDK request. It is not a claim that the current transitive wheels are compatible with GraalPy `25.2.4`, nor a request to redistribute anything. Those are separate technical and legal decisions.

## 2. SGP-00A — evaluation-rights questions

Ask Sarvam or its authorized representative, through the human legal/procurement owner, for written answers to all of the following:

1. May Amsha download, copy, and retain the unmodified `sarvamai==0.1.30` wheel (and, if needed, its sdist) in an access-controlled internal cache for a non-production Phase 0 evaluation?
2. May named Amsha employees and approved contractors execute that package and its required third-party dependency artifacts, including any native `pydantic-core` wheel, in internal JVM and GraalVM Native Image test environments?
3. Does the permission cover build-time GraalPy VFS/resource packaging, first-run extraction of native libraries to an approved temporary or pre-extracted directory, native loading, process shutdown, and sanitized diagnostic evidence? Runtime package installation and package-index access will be disabled.
4. Does the scope cover macOS arm64, Linux x86-64, and Linux arm64, for both JVM and Native Image experiments, while excluding redistribution and production use? Windows is not proposed as a Sarvam-enabled target.
5. May Amsha run non-sensitive, authorized test requests against Sarvam endpoints with a dedicated test account, subject to Sarvam quota, data-processing, retention, and rate-limit rules, only in the later live-provider phase after the named human SGP-06 continue decision and SGP-07 credential/quota approval? This packet does not authorize a live request now and does not approve placing credentials, prompts, responses, or provider data in source, tickets, logs, metrics, traces, crash output, or retained evidence.
6. Are internal copies, build caches, CI workspaces, sanitized crash metadata, and sanitized evidence retained for the period needed for the spike? Identify permitted personnel, locations, retention period, deletion requirements, and any territory restrictions. Raw heap/core dumps are disabled for live-provider tests and are not requested for retention.
7. Is the proposed use of the public SDK API without SDK source modification acceptable? Any Java boundary adapter remains Amsha code; no monkey-patching or vendored SDK modification is proposed.
8. If the generated package or its dependency graph has no published license file, repository history, or support policy, what written terms govern this evaluation? Please identify the covered artifact versions rather than relying on README contribution language.

Evaluation approval must explicitly be limited to internal, non-distributable evidence gathering. It must not be recorded as redistribution permission.

### SGP-00A written evidence and decision

Acceptable evidence is an access-controlled written authorization from Sarvam legal, licensing, or commercial authority, or a signed agreement/order whose terms clearly identify the artifact, activities, environments, people, retention, and restrictions. An internal legal/procurement approval may interpret and record that authority, but PyPI metadata, a README, a 404 repository, an informal chat, or an unreferenced verbal statement is not sufficient.

- **Pass:** the written authority covers `sarvamai==0.1.30`, required dependency artifacts, internal download/copy/cache/execute, VFS/native extraction and load, the proposed JVM/Native Image/platform envelope, and retention/credential restrictions, while expressly excluding redistribution. A human owner records the evidence pointer, date, scope, approver, and the concise evaluation-rights scope record required by SGP-00A.
- **Fail:** Sarvam refuses the activity, limits it so the proposed executable evaluation cannot run, or imposes conditions the project cannot satisfy. Executable SDK spikes remain blocked; static research may remain.
- **Needs clarification:** the response is silent or ambiguous about any artifact, native dependency, VFS/extraction behavior, platform/deployment mode, internal cache, test requests, retention, or named users. Keep SGP-00A unresolved and request a written clarification.

## 3. SGP-00B — redistribution-rights questions

Ask Sarvam and internal legal/procurement to answer these separately from SGP-00A:

This gate decides whether redistribution is authorized. It does not approve Sarvam adoption or an adoption ADR; those remain later Amsha human decisions after all technical evidence gates.

1. May Amsha copy and redistribute `sarvamai==0.1.30` and every dependency artifact required by the graph that local SGP-01 will freeze, including native `pydantic-core` libraries, in an opt-in Sarvam-enabled JVM artifact and an opt-in Native Image artifact? Redistribution approval remains conditional on that later exact graph, not on an unresolved dependency set.
2. Do the rights cover the GraalPy runtime resources and VFS/native extraction payloads needed to run those artifacts, or must any component be obtained separately by the end user?
3. Do the rights cover all three proposed Sarvam platform families (macOS arm64, Linux x86-64, Linux arm64) and both deployment modes? Windows is intentionally outside the Sarvam distribution scope.
4. What license, attribution, notice, source-offer, modification, patent, export, territory, support, and warranty obligations apply to the generated SDK and each direct/transitive dependency? Are any copyleft or field-of-use restrictions present?
5. Does the answer cover internal artifact repositories, CI caches, SBOMs, signed provenance, vulnerability records, customer support bundles, and compressed JAR/native distributions? May Amsha preserve the exact hashes and notices required for each release?
6. Does redistribution permission cover only the unmodified public SDK plus an Amsha boundary adapter, or are generated-source modifications, patches, vendoring, or repackaging restricted?
7. What authoritative source establishes the license and support policy when the PyPI metadata has no `License`/`License-Expression`, the artifacts contain no `LICENSE`, `COPYING`, or `NOTICE`, and the declared repository currently returns 404? The README's statement that Sarvam values open-source contributions is not a license.
8. If terms depend on a future SDK or dependency version, identify the covered versions and the review trigger. The Phase 0 pin is `sarvamai==0.1.30`; a later upgrade needs a new review.

### SGP-00B written evidence and decision

Acceptable evidence is an authoritative license file or written redistribution grant from Sarvam, a signed commercial agreement, or an internal legal opinion that cites and interprets such source terms. It must cover the SDK, transitive/native dependency payload, GraalPy VFS/native packaging, both deployment modes, target platforms, notices/SBOM/source obligations, and any modification or support conditions. README prose, PyPI classifiers, an absent license field, or an unverified repository is not approval.

- **Pass:** authoritative terms explicitly permit the proposed opt-in JVM and Native Image distributions and identify all obligations for the SGP-01-frozen dependency graph. A human owner records the evidence pointer, covered versions/platforms, notice/source plan, generated/third-party support-policy record, date, and approver.
- **Fail:** redistribution is prohibited, the rights are incompatible with the intended artifacts, or no authoritative terms are available after the legal/procurement review. Do not produce a distributable Sarvam artifact and do not adopt; evaluation-only work may proceed only if SGP-00A passed.
- **Needs clarification:** evaluation is permitted but terms omit the generated SDK, native dependency, VFS extraction, one deployment mode/platform, or release obligations. Keep redistribution blocked and obtain a scoped written answer.

An unresolved SGP-00B result is an adoption and distributable-packaging blocker. It does not revoke a separately passed SGP-00A evaluation permission.

## 4. Internal legal/procurement review checklist

The human owner should retain an access-controlled record, not confidential correspondence in a ticket or source document.

- [ ] Confirm the exact request is `sarvamai==0.1.30`; record wheel/sdist hashes and distinguish static inspection from executable evaluation.
- [ ] Inventory the complete direct/transitive dependency graph, including the native `pydantic-core` artifact selected by the resolver; capture exact versions, filenames, platform/interpreter/native-ABI markers, URLs, sizes, hashes, provenance, license status, SBOM entry, and vulnerability disposition. Keep this inventory conditional for redistribution until local SGP-01 completes.
- [ ] Verify the declared PyPI repository and package metadata; record that the artifacts have no license metadata/file and that README contribution language is not a license.
- [ ] Obtain and archive the authoritative Sarvam response or contract reference, including issuer role, company-domain provenance, effective date, covered versions, jurisdiction, and expiry/review trigger.
- [ ] For SGP-00A, confirm download/copy/cache/execute rights, internal users and hosts, JVM/Native Image, VFS packaging, native extraction/load, retention, deletion, and no-redistribution boundary. Record live test-request permission only as a future condition after SGP-06 continue and SGP-07 credential/quota approval.
- [ ] For SGP-00B, confirm redistribution of the SDK, every SGP-01-frozen dependency/native payload, GraalPy VFS/runtime resources, opt-in JAR/native artifacts, target platforms, notices/SBOM/source obligations, modifications, support, warranty, and territory/field-of-use terms. Record the generated/third-party support-policy decision.
- [ ] Confirm whether a dedicated Sarvam test account, API key, data-processing terms, quota, rate limits, and cost ceiling are approved for later SGP-07 live probes after SGP-06 continue. Keep secrets in the approved secret store only; this is a downstream live-test precondition, not rights-gate evidence.
- [ ] For downstream SGP-03/SGP-10, obtain security sign-off for the native-extension posture: process-trusted pinned artifact with accepted blast radius, or a separately verified OS sandbox/container. Python-level permission tests alone are insufficient; this is not established by SGP-00A/00B.
- [ ] For downstream SGP-03/SGP-05/SGP-08, confirm the proposed executor/context rules, transaction boundary, disabled-by-default behavior, and Windows normal-artifact isolation are compatible with internal policy; these are execution preconditions, not rights approval.
- [ ] Confirm zero secret, prompt, response, or provider-data leakage in logs, metrics, traces, crash output, or retained evidence during any later spike; retain only the content-free metadata defined above, with raw heap/core dumps disabled for live-provider tests.
- [ ] Record a single human decision for each gate as **pass**, **fail**, or **needs clarification**, with owner, date, evidence pointer, and explicit restrictions.
- [ ] If either gate fails or remains unresolved, record the downstream blocker rather than waiving it informally. Do not create an adoption ADR or implementation from this packet.

## 5. Draft message to Sarvam

**Subject:** Separate written evaluation authorization and redistribution terms for `sarvamai==0.1.30`

Hello Sarvam team,

Amsha Agent is evaluating the official Sarvam Python SDK for a text-only, non-streaming chat integration, with text translation as a later Phase 0 probe. The exact SDK under review is `sarvamai==0.1.30` from PyPI. Our preferred wheel is `sarvamai-0.1.30-py3-none-any.whl` (SHA-256 `ab586559e041b8dbaa03a536d6c23383f6cda86e9ab9509519408cd54ea66f09`); the published sdist hash is `e53b78ea695351102e880f727715a94cca4d2c7730d9663160066b7618e964d4`.

Please provide an authoritative written response from Sarvam legal/licensing or an authorized commercial representative addressing two separate questions:

**1. Internal evaluation permission.** May Amsha download, copy, retain in an access-controlled internal cache, and execute this unmodified SDK and its required dependency artifacts (including any native `pydantic-core` wheel) for a non-production Phase 0 evaluation? The proposed environments are GraalPy embedded in Quarkus/JDK 25 JVM and GraalVM Native Image, using build-time VFS packaging and controlled native-library extraction, on macOS arm64, Linux x86-64, and Linux arm64. We will not redistribute the package, install packages at runtime, access package indexes at runtime, or place credentials, prompts, responses, or provider data in source, tickets, logs, metrics, traces, crash output, or retained evidence. Retained evidence will be limited to content-free operational metadata and sanitized logs as defined above; raw heap/core dumps will be disabled for live-provider tests. Any live provider request would occur only after the named human SGP-06 continue decision and SGP-07 credential/quota approval. Please state permitted users, hosts, retention/deletion, test-request, territory, and support conditions.

**2. Redistribution terms (adoption remains a separate Amsha decision).** If the evaluation succeeds, may Amsha redistribute an optional Sarvam-enabled JVM artifact and optional Native Image artifact containing this exact SDK, its required dependency/native payload, and the GraalPy VFS/runtime resources? Please identify the authoritative license or written grant, attribution/notice/source obligations, modification limits, support/warranty terms, territories, and whether the permission covers all three target platform families and both deployment modes. Please also state how the generated SDK and its dependencies are supported when the PyPI metadata has no license field/file and the declared repository is unavailable.

We are requesting written scope, not a technical compatibility warranty. GraalPy version/ABI compatibility, Quarkus packaging, native-image behavior, security, performance, and model quality will be evaluated separately. A signed letter, license/contract reference, or response from an authorized Sarvam legal/licensing address is acceptable; please include an effective date and any expiry or version-review trigger.

Thank you,

Srinivasan Ragothaman<br>
Maintainer, Amsha Agent<br>
GitHub: [@rsrini7](https://github.com/rsrini7)

This is a draft. A human must review recipients, organization details, confidentiality language, and the exact artifact scope before sending.

## 6. Human handoff and boundaries

- SGP-00A and SGP-00B may be requested in parallel, but executable SDK work waits for a passed SGP-00A.
- SGP-00B is independent: evaluation-only work may proceed after SGP-00A, while distributable packaging and any adoption decision wait for a passed SGP-00B.
- This packet does not authorize artifact downloads, credential use, external communication, implementation, ticket edits, status promotion, an adoption ADR, or a production release.
