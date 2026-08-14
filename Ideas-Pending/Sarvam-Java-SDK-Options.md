# Sarvam Java SDK Options

**Research snapshot:** 2026-08-14. This note compares the current private
GraalPy spike with Java alternatives. It is a technical recommendation, not
legal advice or an adoption ADR.

## Recommendation

We have enough evidence to choose the next experiment, but not enough to choose
the final implementation.

1. Finish `SGP-04`. Its provider-neutral wire fixtures remain useful even if
   GraalPy is later rejected.
2. Do **not** port or copy `sarvam-ai-sdk` into Java. It is a Vercel AI SDK
   provider, not Sarvam's first-class JavaScript client, and it has no published
   source licence.
3. After `SGP-04`, run a small Java comparison against the same fixtures. Try
   Amsha's existing LangChain4j OpenAI-compatible `ChatModel` first, adding only
   the documented Sarvam subscription-key header. Build a selective typed Java
   client only for contract gaps that this adapter cannot cover.
4. Run `SGP-05` only while GraalPy remains a contender. It is the evidence that
   decides whether GraalPy's concurrency, cancellation, resource, and overhead
   costs are acceptable.

The likely long-term fit for Amsha is the thin Java path. Sarvam explicitly
describes `/v2/chat/completions` as OpenAI-compatible in its
[published OpenAPI](https://docs.sarvam.ai/openapi/apis.json), while the current
Phase 0 target is the separate `/v1/chat/completions` contract. Amsha already
uses LangChain4j's `ChatModel` seam, so compatibility should be proved rather
than assumed. A typed SDK is still an HTTP client; its value is typed models,
error policy, retries, redaction, and stable tests—not avoiding HTTP itself.
Sarvam's [authentication guide](https://docs.sarvam.ai/api-reference/authentication)
also says bearer authentication supports OpenAI-compatible tooling.

## What the two TypeScript packages actually are

| Package | First-party description | Relevance to Java |
| --- | --- | --- |
| [`sarvamai`](https://www.npmjs.com/package/sarvamai) | Sarvam lists this and the Python package as its only first-class SDKs. Both package readmes identify generated SDKs; the JavaScript archive identifies Fern throughout, while the pinned [Python package](https://pypi.org/project/sarvamai/0.1.30/) says it is generated programmatically. The JavaScript client provides auth, serialization, typed errors, timeouts, retries, REST, SSE, and WebSocket surfaces. See [SDKs and libraries](https://docs.sarvam.ai/api/getting-started/sdks). | Useful for understanding supported product behavior, but its declared GitHub repository was not publicly cloneable in this snapshot. The npm package declares no licence and its archive omits the `LICENSE` named in `package.json`; do not copy its generated source. |
| [`sarvam-ai-sdk`](https://github.com/sarvamai/sarvam-ai-sdk) | Sarvam's own documentation calls it a **community provider for the Vercel AI SDK**, not the first-class JavaScript SDK. See the [Vercel integration guide](https://docs.sarvam.ai/api/integration/vercel-ai-sdk). | It translates Vercel AI SDK abstractions into Sarvam calls. Porting those Node/Vercel abstractions would add irrelevant Java surface area. |

At commit
[`956f54e`](https://github.com/sarvamai/sarvam-ai-sdk/tree/956f54e512c5dcf19c10358e1741af6850a1bf96),
the public provider is a small handwritten adapter with Zod schemas, not an
OpenAPI-generated general client. It covers chat, speech-to-text,
text-to-speech, translation, transliteration, and language identification
through Vercel interfaces; its
[`package.json`](https://github.com/sarvamai/sarvam-ai-sdk/blob/956f54e512c5dcf19c10358e1741af6850a1bf96/package.json)
contains no generator configuration or licence. The repository tree contains no
`LICENSE`, `COPYING`, or `NOTICE`, and GitHub reports no detected licence in the
[repository metadata](https://api.github.com/repos/sarvamai/sarvam-ai-sdk).

The useful behavioral facts are already documented independently:

- the provider sends both bearer and `api-subscription-key` headers and permits
  an injected fetch implementation
  ([provider source](https://github.com/sarvamai/sarvam-ai-sdk/blob/956f54e512c5dcf19c10358e1741af6850a1bf96/src/provider.ts#L15-L42));
- chat maps messages, tools, structured output, usage, JSON errors, and SSE
  chunks
  ([chat source](https://github.com/sarvamai/sarvam-ai-sdk/blob/956f54e512c5dcf19c10358e1741af6850a1bf96/src/chat/language-model.ts#L61-L295));
- translation is a separate non-streaming call
  ([translation source](https://github.com/sarvamai/sarvam-ai-sdk/blob/956f54e512c5dcf19c10358e1741af6850a1bf96/src/ttt/translation-model.ts#L98-L170));
- its own error module validates a response envelope but does not implement the
  complete first-class SDK retry policy
  ([error source](https://github.com/sarvamai/sarvam-ai-sdk/blob/956f54e512c5dcf19c10358e1741af6850a1bf96/src/error.ts)).

The current npm release is `0.4.2`, while the public default branch still says
`0.3.1` and has no release tags. The
[npm registry record](https://registry.npmjs.org/sarvam-ai-sdk) also declares no
licence. This provenance gap is another reason not to use the repository as the
canonical Java source.

## What can be implemented in Java

Sarvam publishes an
[OpenAPI 3.1 document](https://docs.sarvam.ai/openapi/apis.json) and an official
[chat reference](https://docs.sarvam.ai/api-reference/chat/chat-completions).
The 2026-08-14 OpenAPI snapshot had 38 paths, 156 schemas, 38 `oneOf` uses,
four discriminators, and no `info.license`; its SHA-256 was
`e5c9de1c45d5815905278d7260995d7a760b33c9a6009d5f78f1148bea4226da`.

Technically possible now:

- a typed, non-streaming Java chat client for system/user/assistant/tool
  messages, model settings, structured output, tool calls, usage, request IDs,
  timeouts, retries, and typed errors;
- an SSE streaming client later, although streaming is outside the current
  Amsha Phase 0 product boundary;
- a separate typed translation call;
- a broad generated client for the REST API, plus separately implemented
  WebSocket support.

Not established or not advisable now:

- calling a new Java client an official or Sarvam-supported SDK;
- copying TypeScript implementation, tests, descriptions, or generated models;
- claiming source parity from the Vercel provider;
- publishing code generated verbatim from the unlicensed OpenAPI document
  without written rights clarification;
- promising full speech, document, batch, SSE, and WebSocket parity before those
  contracts are independently tested;
- treating the generated Java request snippet in the chat reference as an SDK.
  It is a raw Unirest example, and Sarvam explicitly says only Python and
  JavaScript are first-class SDKs
  ([SDK guidance](https://docs.sarvam.ai/api/getting-started/sdks)).

OpenAPI generation is possible but not turnkey. The published contract needs a
small reviewed overlay: the general authentication guide says either
subscription-key or bearer authentication is accepted, while the chat OpenAPI
operation models bearer as a required parameter and the subscription key as
optional. Error and retry guidance also extends beyond the response codes on
individual operations. These are specification-drift tests, not details to
hide in generated code. See the official
[authentication](https://docs.sarvam.ai/api-reference/authentication) and
[error/retry](https://docs.sarvam.ai/api/getting-started/errors-troubleshooting)
guides.

## Reuse and publication boundary

Publicly readable source is not automatically open source. Neither public
TypeScript package metadata nor the `sarvam-ai-sdk` repository supplies a
licence grant. Sarvam's current
[Terms of Service](https://www.sarvam.ai/terms-of-service) allow customers and
personal users to build their own products through authorized developer APIs
(sections 1.2 and 4.1), but also restrict copying, derivative works, reverse
engineering, recreating Offerings from observed inputs/outputs, and integrations
outside authorized interfaces (sections 9–11).

Therefore:

- private technical comparison can continue under the existing Phase 0A risk
  decision;
- use the official API reference as the interoperability contract and author
  Java behavior independently;
- do not translate files, preserve TypeScript structure, copy tests/comments, or
  use the TypeScript repository as the implementation oracle;
- retain independent design records, authorship, literal contract fixtures, and
  a pinned official-spec digest;
- obtain written permission or a clear SDK/OpenAPI licence before publishing a
  generated client or anything derived from Sarvam source. An original thin
  adapter reduces the redistribution problem, but does not by itself resolve
  `SGP-00B` or the applicable API terms.

## Concrete Java option

Use a deep module at Amsha's existing LangChain4j seam:

```text
Amsha callers
    -> dev.langchain4j.model.chat.ChatModel
        -> SarvamChatModel adapter
            -> OpenAI-compatible transport or selective Sarvam wire client
                -> /v1/chat/completions
```

The interface remains `ChatModel.chat(ChatRequest) -> ChatResponse`. Callers do
not learn Sarvam wire DTOs, HTTP headers, retry rules, or generator types. A
builder/factory supplies model, secret provider, timeout, retry ceiling, and an
injectable transport for tests.

The implementation hides:

- LangChain4j-to-Sarvam message, tool, structured-output, finish-reason, usage,
  and error mapping;
- subscription-key/bearer authentication, secret redaction, request ID
  propagation, timeout, cancellation, `Retry-After`, and bounded retries only
  for approved transient failures;
- JSON validation and unknown-field tolerance;
- pinned OpenAPI provenance and any generated wire models;
- Quarkus CDI registration, readiness, disabled-mode isolation, telemetry, and
  Native Image reflection metadata.

First implementation choice: use the already-present LangChain4j OpenAI client
with Sarvam's base URL and a custom `api-subscription-key` header. If the shared
`SGP-04` fixtures expose missing fields or incorrect error behavior, replace the
internal transport with a selective JDK `HttpClient` plus Jackson implementation.
The `ChatModel` interface and its tests stay unchanged. Translation remains a
separate compatibility probe rather than expanding the chat interface.

Dependencies should remain the existing LangChain4j core/OpenAI artifacts for
the first probe. A dedicated fallback needs only JDK `HttpClient`, Jackson, and
the existing LangChain4j core contract. If generation is chosen, pin the
generator at build time and keep generated wire types internal; do not make the
runtime depend on GraalPy, Python, Pydantic, Node, Vercel AI SDK, or Sarvam's
TypeScript packages.

This design is reusable by other LangChain4j applications and concentrates
provider drift in one module. It is intentionally not a full Java clone of
Sarvam's Python/JavaScript SDKs.

## Evidence required for the decision

| Evidence | What it decides |
| --- | --- |
| `SGP-03` resolved | Already proves that the pinned Python SDK imports, loads its native dependency, packages offline, and shuts down on the private macOS-arm64 JVM harness. It is sufficient for “can GraalPy run it?”, not for adoption. |
| `SGP-04` | Proves the real Python SDK's required chat and translation wire behavior. Its canonical fixtures should be implementation-neutral and reused by Java. Do not discard or interrupt this work. |
| Small Java contract spike | Run the same `SGP-04` chat cohort through the LangChain4j path, then through a selective client only if needed. Record request/response parity, retries, errors, dependency delta, startup/RSS, normal-artifact isolation, JVM compile, and Native Image reachability metadata. This evidence does not exist yet. |
| `SGP-05` | Required only to keep GraalPy in the final comparison: bounded concurrency, context propagation, cancellation, shutdown, and GraalPy-versus-CPython overhead. |
| `SGP-07` | Not required to choose the transport. Live provider/model behavior is common to both paths and remains separately gated. |
| `SGP-08`–`SGP-10` | Required before adopting a GraalPy variant across the declared JVM/Native Image matrix. A Java adapter still needs normal Amsha platform/native verification, but avoids the Python ABI, native-extension, VFS, and `HostIO.READ` gates. |

The decision point is after `SGP-04` plus the small Java contract spike. Choose
the thin Java module if it passes the same primary chat contracts without a
provider-specific loss that matters to Amsha. Continue to `SGP-05` only if the
Python SDK supplies material behavior that the Java path cannot reproduce from
the authorized public contract, or if using the official SDK is itself a
non-negotiable product goal.
