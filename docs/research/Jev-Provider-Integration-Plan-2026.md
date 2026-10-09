# Jev Provider Integration Plan

**Date:** 2026-10-01
**Status:** Plan plus Phase 0 verification evidence. No provider activation approved; the ADR remains **Deferred**.
**Scope:** TypeSafe/System One (closed hosted service) and self-hosted/open-weight Jev-style decision models

> **Single consolidated document.** This file holds the plan *and* the Phase 0 evidence that the plan
> listed as its first blocker — the verified TypeSafe contract, the wire-conformance findings, and the
> candidate matrix with the Laya/Kev/CLM detail. It supersedes the separate Phase 0 verification note
> (`Jev-Provider-Verification-2026-10-01.md`), which has been merged into §1–§6 here.

## Verdict summary

1. **TypeSafe's contract is verified from first-party documentation.** Endpoint, auth, model IDs,
   pricing, rate limits, primitives, response schema, error codes, and data-handling terms are all
   confirmed (§1). Console host and API host are **different services**.
2. **The Amsha remote adapter did not speak the real TypeSafe wire contract.** It was roughly
   one-third conformant: `choice` round-tripped, `score` and `noul` would have **failed closed**, and a
   live call would have been rejected with `422`. The fixtures passed because they encoded the same
   incorrect shape. **This is fixed** — see §2 "Resolution".
3. **Laya and Kev *are* Jev wire-compatible — at their serving layer.** Both expose
   `POST /v1/systemone` in the Jev request/response shape, so "no open-weight model implements the Jev
   wire contract" is **false**. This was the plan's largest correction.
4. **The specific artifact downloaded into omlx (`sahilchachra/Laya-TypedDecisions-MXFP8`) cannot make
   decisions.** It is the ModernBERT **encoder backbone only** — the decision head is not included, it
   is not loadable by Laya's own API, and LM Studio cannot serve it. It is a feature extractor, not a
   decision provider (§4).
5. **CLM is a genuine second candidate, and its MLX port is more complete than the Laya one** — but it
   still cannot be served from omlx, and it needs a **prose** `state` rather than a JSON one (§5).

## Recommendation

Treat Jev as a **typed decision protocol and evaluation surface**, not as another ordinary
chat-completion provider. Keep one Amsha-owned `DecisionEngine` contract and add provider
adapters behind it. A provider must produce valid bounded `Choice`, `Score`, or `Noul` results
(or `NoOpinion`); it must not own policy, authorization, HITL, routing side effects, or tool
execution.

The branch already has three architecture lanes: embedded in-process engines, co-located
self-hosted engines, and remote providers. As of 2026-10-01 both the remote and self-hosted
adapters speak the documented `POST /v1/systemone` contract through one shared mapper, so they are
conformant scaffolding rather than placeholders — but they remain unwired: the ADR keeps them
disabled, the routing pilot is Deferred, and no credentialed TypeSafe call has been made.

**Corrected 2026-10-01:** the claim that no open-weight model implements Jev's wire contract was
wrong. Laya (`laya-serve`) and Kev (`kev-server`) both expose `POST /v1/systemone` in the Jev
request/response shape and are Apache-2.0. What remains unproven is that either passes Amsha's
*typed-result* contract end to end — no candidate has been run through the conformance suite, and the
adapter's own decoder only began accepting the real answer shapes on 2026-10-01. See §2–§4.

## What "industry direction" should mean for this feature

For Amsha, the durable direction is **provider portability plus measured model selection**:

- Keep the typed request/result contract independent of any vendor SDK or model family.
- Expect adapters to differ in protocol, available metadata, confidence semantics, and operational
  guarantees. Preserve missing confidence/distributions as missing; do not synthesize them.
- Compare hosted and self-hosted candidates on the same versioned decision corpus and operational
  measures. Open weights improve deployment and data-control options, but do not by themselves
  prove output compatibility, calibration, safety, or lower total cost.
- Treat a model as Jev-compatible only after it passes the Amsha wire-contract suite. Ordinary
  OpenAI-compatible chat or JSON output is not sufficient.

This is an architecture recommendation, not a claim about current vendor releases. **The live TypeSafe
service contract has now been verified** from first-party documentation and is recorded in §1,
together with the conformance gaps that existed between that contract and the adapter before the
2026-10-01 mapping fix — those gaps are now closed for both lanes. Note in particular that
`console.typesafe.ai` is the account console (where keys are issued), while the inference endpoint is
`https://api.typesafe.ai/v1/systemone`.

## 1. TypeSafe / System One — verified first-party contract

### Hosts

| Purpose | Host |
|---|---|
| Console (playground, API keys) | `https://console.typesafe.ai` — playground at `/playground`, keys at `/keys` |
| API (evaluation) | `https://api.typesafe.ai` |

The URL in the original request, `console.typesafe.ai`, is the **account console**. It is where an API
key is issued; it is not the inference endpoint. A provider integration targets `api.typesafe.ai`.

### Endpoint and authentication

```
POST https://api.typesafe.ai/v1/systemone
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

- Key is issued at `console.typesafe.ai/keys` and read by the official SDK from `TYPESAFE_API_KEY`.
- `GET https://api.typesafe.ai/v1/models` lists the names an account may send in `model`.
- Official SDKs: `typesafe-sdk` (Python ≥ 3.10) and a JavaScript SDK. SDKs retry with backoff and honor
  `retry-after`; the raw HTTP API does not retry for you. Amsha deliberately uses the raw governed
  transport instead, because importing an SDK would bypass DNS/TLS/proxy policy.

### Models

| Name | Meaning |
|---|---|
| `jev-1.13.0` | The current versioned model ID |
| `jev-latest` | Alias → `jev-1.13.0` (default in the SDKs) |
| `jev-preview` | Alias → `jev-1.13.0` (no preview build currently published) |

`model` is a **required** request field. The response echoes the **versioned** ID that answered in its
own `model` field, so an alias can move without a client change — pin the versioned ID when confidence
thresholds have been tuned.

### Limits, pricing, context

- **Price:** $42 per Btok = **$0.042 per Mtok**, charged on **input tokens only**; output tokens are free.
- **Rate limits:** 100K tokens/second and 40 requests/second. Documented as **dynamically adjusting and
  subject to change without notice**; over-limit returns `429`.
- **Context:** 64k tokens per request total, of which the `state` plus the **longest single question**
  may be at most 32k.
- **Input:** text only — string, JSON object, or array of text. No image, audio, or video.
- **Language:** English is primary. CJK is handled but weaker; the docs advise testing non-English
  workloads and leaning on confidence when routing.

### Primitives and question shape

Every question carries a lowercase `type` and a **required** `instructions` (string, object, or array):

| `type` | `criteria` shape | Returns |
|---|---|---|
| `choice` | **map** option → description (`null` allowed), max 255 options | `choice`, `probabilities`, `confidence` |
| `score` | **ordered array** of level descriptions, 2–10 levels | `score`, `legend`, `probabilities`, `confidence` |
| `noul` | optional map `{ "true": ..., "false": ... }` | `noul` (0–1) |

All questions in a call are evaluated in parallel against one `state`, and adding questions barely
changes latency. The docs recommend decomposing broad judgments into atomic questions and combining
results in code.

### Response shape

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "department": { "type": "choice", "choice": "technical", "confidence": 0.78,
                    "probabilities": { "technical": 0.85, "sales": 0.0, "billing": 0.15 } },
    "frustration": { "type": "score", "score": 1.0, "confidence": 1.0,
                     "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
                     "probabilities": { "0": 0.0, "1": 1.0, "2": 0.0 } },
    "is_urgent": { "type": "noul", "noul": 1.0 }
  },
  "usage": { "input_tokens": 392, "output_tokens": 65 }
}
```

Note the shapes that matter for decoding: `noul` is a **top-level key named `noul`**, and both `legend`
and `probabilities` for a Score are **objects keyed by level index as a string**, not arrays.

### Error codes

| Status | Meaning |
|---|---|
| `401` | Missing or invalid API key |
| `422` | Request body failed validation; body names the offending field |
| `429` | Rate limit exceeded — back off and retry |
| `529` | **Overloaded** — retry after a short delay |

There is no documented `503`. `529` is TypeSafe's overload signal and is the one an adapter most needs
to map. The documented remedy for both `429` and `529` is exponential backoff; the SDKs do this
automatically. Amsha maps all three (`429`/`503`/`529`) to `NoOpinion(OVERLOAD_REJECTED)` and does not
retry at the transport layer, so the coordinator governs its own turn budget.

### Data handling

- Jev is **not trained on customer requests or responses**, and is not fine-tuned or LoRA-adapted per
  account — the same weights serve every tenant.
- Legal set: Data Processing Agreement, Master Customer Agreement, Privacy Policy.
- **Zero data retention (ZDR)** is available to enterprise customers via `sales@typesafe.ai`.

This satisfies `RemoteProviderConfig`'s non-negotiable `retentionPolicy.allowTrainingUse() == false`
constraint, but the **retention window and data residency must still be declared explicitly** by
whoever configures the provider — the docs do not publish a default retention period.

### Re-verified against the official documentation (2026-10-01)

Every claim above was re-checked against first-party docs at `https://docs.typesafe.ai/` (`/api`,
`/models`, `/introduction`, `/concepts/system-one`). All of them hold, including the **100K tokens/s and
40 requests/s** limits and the **$0.042/Mtok input** price. Three details were sharpened or added:

- **`GET /v1/models`** returns `models[]` entries with `name`, `description`, and `release_date`. It lists
  the aliases; versioned IDs such as `jev-1.13.0` are accepted in `model` whether or not they are listed.
- **A Score can land between levels.** `score` is the probability-weighted answer across the rubric, so it
  may be fractional (`1.05`) rather than an integer level index. `DecisionPayloadDecoder.decodeScore`
  already accepts this — it requires only that the value be finite, with no integer or range check — but
  every fixture used whole numbers, so the case was unexercised. **Now pinned** by
  `DecisionPlaneEvaluationTest.scoreBetweenRubricLevelsDecodesAsFractional`, which decodes a `1.05` score
  in the documented index-keyed shape and asserts it survives verbatim alongside its legend, probabilities,
  and most-likely level.
- **`instructions` may be a string, object, or array**, and a structured `instructions` object can
  reference nested `state` values by name in backticks. `SystemOneWireMapper` always emits a plain string,
  so that capability is currently unused — a deliberate simplification, not a contract limit.

Caution on provenance: a widely-mirrored third-party guide reports the rate limits as "250k tokens/s +
1200 req/min", which contradicts the official `/models` page. Third-party mirrors of this contract can be
wrong — cite `docs.typesafe.ai`.

## 2. Wire-contract conformance: Amsha codec vs live TypeSafe

This was the actionable finding, and the tables below describe the **pre-fix** state. They are retained
because they are the rationale for the mapping layer's shape; the fix itself is recorded under
"Resolution" at the end of this section.

### Request direction

| Element | Amsha emitted | TypeSafe expects | Effect |
|---|---|---|---|
| Path | configurable; default `https://decision-plane.amsha.internal/v1/evaluate`; tests used `/v1/evaluate` | `POST /v1/systemone` | Config-only, but the built-in default is a placeholder host |
| `model` | **absent** | **required** | `422` |
| question discriminator | `"primitive": "CHOICE"` (uppercase enum name) | `"type": "choice"` (lowercase) | `422` |
| `instructions` | **absent** | **required** | `422` |
| Choice option set | `"options": ["A","B"]` | options are the **keys of `criteria`** | `422` / silently wrong |
| Choice/Score rubric | `"criteria": "<free text>"` (a String) | `criteria` is a **map** (Choice) or **array** (Score) | `422` — wrong slot *and* wrong type |
| Score rubric levels | `"rubricLevels": [...]` | belongs in `criteria` as the ordered array | `422` |
| `decisionId`, `schemaVersion`, `timeoutMs` | present | not in the schema | Unknown. TypeSafe does not document unknown-field handling; Laya ignores them. Risk of `422`. |
| `state` | JSON string of the projected state | string, object, or array | OK — a JSON string is accepted |
| `Authorization` | `Bearer <apiKey>` | `Bearer <API_KEY>` | OK |

The mapping problem is structural, not cosmetic: Amsha's `DecisionQuestion.criteria` is the free-text
instruction, which corresponds to TypeSafe's **`instructions`**, while Amsha's `options` /
`rubricLevels` correspond to TypeSafe's **`criteria`**. The two vocabularies name the same two concepts
in opposite slots.

### Response direction

| Answer | TypeSafe returns | `DecisionPayloadDecoder` accepted | Effect |
|---|---|---|---|
| Choice | `{"type":"choice","choice":..., "probabilities":{...}, "confidence":...}` | `choice` + map `probabilities` + `confidence` | **OK** |
| Noul | `{"type":"noul","noul":0.95}` | `probability` or `value` key only | **fails closed** → `NoOpinion(INFERENCE_ERROR)` |
| Score `probabilities` | object `{"0":0.0,"1":1.0}` | must be a JSON **array** | **fails closed** |
| Score `legend` | object `{"0":"Calm","1":"Frustrated"}` | must be a JSON **array** of strings | **fails closed** |
| Top-level model attribution | `model` | reads `modelId` | metadata lost (not fatal) |

### Why the tests did not catch this

`RemoteDecisionEngineContractTest` is a genuine fixture harness, but its fixtures encoded the same
assumptions as the codec: the noul fixture returned `{"probability": 0.98}`, the score fixture returned
`legend` as an array, and every case posted to `/v1/evaluate`. The suite therefore proved internal
self-consistency, not vendor conformance. A green run was **not** evidence that TypeSafe would work.

This is the same failure mode already recorded for other fixtures in this repository: a test that
asserts against a hand-written shape passes whether or not the shape is real. The fix is to capture
the response bodies from §1 verbatim as fixtures — and, going further, to assert the *emitted* request
structurally rather than by substring.

### Consequence

The remote seam's governance, transport, redaction, placement, transaction-guard, and no-retry
behaviour are all sound and independently tested — that is the hard part and it is done. What was
missing was a thin vendor mapping layer plus truthful fixtures. Phase 1 was therefore **smaller than it
looked**, but **mandatory before any credentialed sandbox call**: as written, a live TypeSafe call
would have returned `422` on the request and, if it had not, `score`/`noul` answers would have been
discarded as malformed.

### Resolution (2026-10-01)

The mapping layer and truthful fixtures have landed, so the tables above now describe the pre-fix
state:

- **`SystemOneWireMapper`** (new, `core/…/classifier/wire/`) emits the contract vocabulary —
  lowercase `type`, required `instructions`, and `criteria` as an option-keyed map for Choice or the
  ordered rubric array for Score — omits `decisionId`/`schemaVersion`/`timeoutMs`, and returns an
  `Unrepresentable` outcome instead of sending when a question has no valid representation (a Score
  outside 2–10 levels). Both the remote and self-hosted lanes now use it, so the two copies of the
  request builder cannot drift apart again.
- **`RemoteProviderConfig`** gained a required `modelId` field with **no default**. An earlier revision
  coerced a blank value to the floating alias `jev-latest`, which contradicted
  `DecisionProviderSelector` (a remote slot with no model is refused rather than defaulted) and would
  have substituted an unpinned alias for the versioned id an operator meant to pin — defeating the
  provenance the decoder now reports back through `DecisionMetadata`. A blank or absent value is
  rejected instead, so the two layers state one rule. The self-hosted config gained an optional
  `modelId` that is omitted when blank, because a self-hosted server selects its own checkpoint and
  would misread a hosted vendor's alias.
- **`ProviderPayloadParser`** (new, `classifier-api`) plus decoder changes accept the real answer
  shapes: the Noul `noul` key, Score `legend`/`probabilities` as index-keyed objects (normalized to
  positional lists, rejecting gaps, non-canonical keys, and out-of-range indices), top-level `model`
  attribution, and nested `usage` token counts under either the System One names (`input_tokens` /
  `output_tokens`) or the OpenAI-compatible ones (`prompt_tokens` / `completion_tokens`) that local
  runtimes such as LM Studio and Ollama return. The alias list widens the nested `usage` object only;
  the top-level keys stay exact. It has a dedicated contract suite
  (`ProviderPayloadParserTest`, 36 tests) covering the rejection paths the decoder's own tests never
  reached — the fail-closed guarantee lives in those branches, not in the happy path. Line coverage went
  **47/70 → 70/70** and branch coverage **35/62 → 61/62**.
- **One branch in that parser is unreachable, and is now documented as such.** The remaining uncovered
  branch is the duplicate-index collision test in the index-ordering helper. It cannot fire: `Map` keys
  are unique by contract, and the accepted key pattern is canonical (no sign, no whitespace, no leading
  zeros), so `Integer.parseInt` is injective over the accepted keys and two entries can never resolve to
  the same slot. An earlier draft of this document claimed the parser rejects "duplicates" alongside gaps
  and non-canonical keys; that claim was wrong, because a duplicate index is not a representable input.
  The guard is retained rather than deleted, in case the pattern is later relaxed to accept aliases.
- **`529`** now maps to `NoOpinion(OVERLOAD_REJECTED)` — the status TypeSafe actually returns.
- Fixtures were replaced with the documented bodies and now assert the emitted request body
  **structurally** — exact envelope and question key sets, rather than substring matching, so a field in
  the wrong slot or an extra field fails the test — plus the attributed model and the usage token
  counts. The Choice fixture additionally carries two Amsha envelope fields (`ruleId`, `latencyMs`) that
  the documented response does not contain; that is deliberate, to exercise tolerance of extra envelope
  keys, and is labelled as such in the test rather than presented as a verbatim vendor body.

Verified: the affected suites pass (61 tests in `classifier-api`, 70 in `core`), including the new
`SystemOneWireMapperTest`, and a full-module re-run of both modules is green — 3,465 tests, 0 failures,
1 skipped; `spotless:apply` clean and the module PMD gate green. The self-hosted lane reached wire
parity on the same day, so both lanes are now covered by the contract suites.

Two later passes closed the remaining "green but vacuous" gaps. `ProviderPayloadParserTest` (26 tests)
covers the parser's fail-closed branches, taking the `classifier-api` module to **128 tests**; and
`DecisionProviderConfigBindingTest` (5 tests) proves in-container that the `@ConfigMapping` binds and
that configuring a provider still leaves `DecisionEngine` unresolvable (§8). Both were re-verified with
`spotless:apply` and the module PMD gate green. Still outstanding: no credentialed sandbox call, and the
retention window and data residency remain undeclared.

## 3. Candidate matrix

| Candidate | Kind | License | Wire protocol | Serves `POST /v1/systemone` | Notes |
|---|---|---|---|---|---|
| **TypeSafe Jev 1.13.0** | Closed hosted API | Proprietary service | Jev (reference) | Yes — this *is* the reference | $0.042/Mtok input, 40 rps, 100K tok/s, 64k context, no training on customer data, ZDR on enterprise |
| **Laya** (`convaiinnovations/laya`) | Open weights, self-hosted | **Apache-2.0** | Jev-compatible at `laya-serve` | Yes | Non-autoregressive; ModernBERT-large 421M + decision head; `pip install "laya[serve]"`; 33 ms single question (T4) |
| **Kev** (`arjun988/Kev`) | Open source server, BYO model | **Apache-2.0** | Jev-style | Yes | Node 20+; backends `mock`/`ollama`/`openai`; default `qwen3.5:9b`; p50 504 ms / p95 803 ms; **LLM-backed, not a dedicated decision model** |
| **Decis** (`chaitin/Decis`) | Open source inference server | (not yet verified) | Jev-compatible | Yes | Self-hostable server for open decision models; listed for completeness |
| **CLM** (`Contrastive-LM/CLM`, `clm-serve`) | Open weights, self-hosted | **Apache-2.0** (heads + code; encoder Qwen3-8B also Apache-2.0) | Jev — `POST /v1/systemone` | Yes | Contrastive System One model: frozen Qwen3-8B encoder + state/action projection heads. **No generation.** Two processes: vLLM Qwen3-8B pooling on `:8090` + `clm-serve` on `:8700`. 2048-token limit. See §5 |
| **`mlx-community/CLM-v0.1-8B-MLX-8bit`** | Community MLX port of the above | Apache-2.0 | **Python library — no HTTP server** | **No** | Ships the encoder **and the heads** (`clm_mlx`), so it *can* decide — unlike the Laya MXFP8 artifact — but it is a library, not a server, and LM Studio/MLXUI cannot serve it. See §5 |
| **`sahilchachra/Laya-TypedDecisions-MXFP8`** | Encoder-only MLX quantization | inherits Laya (Apache-2.0) | **none** | **No** | Not a decision model — see §4 |

Two corrections to earlier assumptions:

- The plan and the scout both treat open-weight Jev wire compatibility as unproven. It is proven, at
  the serving layer, for Laya and Kev. What is *not* proven is that any candidate passes Amsha's
  **typed result contract** end to end — a vendor can accept the request shape and still return
  answers that fail Amsha's stricter decoder (exactly as TypeSafe's own Score and Noul shapes did).
- Kev is not a small decision model. It is an orchestration server that coerces a general LLM into
  typed output. That changes the cost, latency, and determinism profile relative to Laya and Jev, and
  it means Kev's accuracy numbers are Qwen3.5:9b's numbers, not a dedicated decision head's.

## 4. Laya — what the downloaded artifact actually is

The original request named `sahilchachra/Laya-TypedDecisions-MXFP8` as "my omlx downloaded laya
decision model". Its own model card is explicit that it is **not** one:

- It is the **Typed-Decisions encoder backbone only** — ModernBERT-large (421M params, 1024 hidden,
  28 layers, 1024-token context) quantized to MXFP8 for MLX on Apple Silicon, ~407 MB.
- Laya is `ModernBERT-large` **plus a from-scratch decision head** (a 2-layer `TransformerEncoder`,
  an option-marker scorer, and an act/escalate head) defined in Laya's own `rl_common.py`. **Only the
  encoder is published in this repo.** The head is a ~15M-parameter torch module that is not included.
- Porting the head to MLX "would not make Laya's `laya.load(...)` API usable anyway" — that API expects
  a torch model directory as-is. So this repo is **not loadable by Laya's decision API**.
- **LM Studio cannot load or serve it**: there is no `generate()` path and no chat template. LM Studio
  only runs chat/completion-style models.
- Quantization caveat, stated honestly by the author: this is a small LayerNorm-heavy encoder, not an
  LLM, and is unusually sensitive to block quantization. MXFP8 shows a decision-head logit max-abs
  difference of 0.646 versus fp32, and **one of three test questions flipped its top-1 answer**. The
  guidance is to treat quantized confidence as directional, not exact.

**Implication.** To use Laya as a decision provider, run the upstream `convaiinnovations/laya` package
and its `laya-serve` HTTP server — not this MLX encoder repo. The MLX artifact is useful as a
quantized feature extractor or as the basis for a custom head, which is a different project from
provider integration.

### Laya's serving facts (the part that matters for integration)

```bash
pip install "laya[serve]"
LAYA_DEVICE=cuda LAYA_PRELOAD=1 laya-serve   # binds 0.0.0.0:8000
```

- Exposes `POST /v1/systemone` and `POST /v1/systemone/batch` in the **Jev request/response shape** —
  "existing TypeSafe clients work by changing their base URL".
- Accepts every question shape Jev does, **ignores unknown fields**, and returns `422` naming the
  problem for a malformed question.
- Binds `0.0.0.0` with **no authentication unless `LAYA_API_KEY` is set**, in which case it requires
  `Authorization: Bearer <key>`.
- Three checkpoints behind a `Router`: `laya` (ModernBERT-large, English, 512 ctx),
  `laya-multilingual` (mmBERT-base, 100+ languages, 1024→8192 ctx, ~2.2× faster),
  `laya-typed-decisions` (ModernBERT-large, 1024 ctx).
- Python ≥ 3.10. CPU, CUDA, Apple MPS, Intel XPU, and Ascend NPU paths exist.

Documented limits worth carrying into evaluation: base checkpoints are **near chance on typed-decisions
zero-shot** (0.362 vs a 0.461 majority baseline) and reach 0.766 only after fine-tuning; both
checkpoints are **over-confident as shipped** and need temperature calibration; high-cardinality Choice
(>20 options) degrades badly (Banking77: Jev 0.870 vs Laya 0.425); and the `action.act_probability`
head currently carries no usable signal, so gating should use `confidence`.

## 5. CLM (Contrastive Language Model) — verified (2026-10-01)

Raised as "`mlx-community/CLM-v0.1-8B-MLX-8bit` also supports the same decision model, like Laya". **The
protocol claim is correct, and this artifact is materially better than the Laya one** — but it still
cannot be served from omlx. Both halves of that matter.

### What CLM is

CLM is a genuine System One model: a frozen **Qwen3-8B encoder** with two small projection heads (a state
head and an action head, ~20M params) trained with a bidirectional InfoNCE contrastive objective. It is
**not an LLM** — it scores the candidates you give it, and its probabilities are relative to that set.
Zero-shot it is reported on par with Jev on computer-use, gaming, and tool-calling at up to 9× lower
latency; the SOTA agentic benchmark numbers come from *fine-tuned* heads, not this checkpoint.

### The protocol claim holds

Upstream `clm-serve` exposes **`POST /v1/systemone`** — the same route TypeSafe, Laya, and Kev use — with
`noul` / `choice` / `score` questions and typed answers. A TypeSafe client reaches it by changing the base
URL, exactly as with Laya.

| Aspect | TypeSafe Jev | CLM |
|---|---|---|
| Route | `POST /v1/systemone` | `POST /v1/systemone` (same) |
| `model` values | `jev-latest`, `jev-1.13.0` | `clm-latest` (default), `clm-raw` |
| Auth | `Authorization: Bearer` | `CLM_API_KEY` → `Authorization: Bearer`; unset means **no auth** |
| Default bind | n/a (hosted) | `127.0.0.1:8700` — loopback by default, unlike `laya-serve` |
| Extra request field | — | `temperature` (0–100], default 1 |
| Errors | `401` `422` `429` `529` | `401` `422` **`502`** (embedder unreachable) |

### Four differences that would break a naive swap

1. **`state` must be prose, never JSON.** Upstream says so explicitly: objects render as `key: value` text
   and arrays as `- item` lines, because the heads were trained on prose. `SystemOneWireMapper` originally
   sent `state` as a **JSON string** — which the TypeSafe contract explicitly accepts, and which our
   fixtures assert. Against CLM those same bytes are semantically wrong and would degrade answers, silently
   rather than loudly. **This is the most important finding in this section**, and it is now handled: see
   §8 "Wire profiles".
2. **`confidence` is not comparable across providers.** CLM defines it as *top probability minus the mean
   of the others*; TypeSafe reports a calibrated confidence derived from the distribution. A confidence
   threshold tuned on one provider does not transfer to the other, which directly constrains the Phase 3
   review-band design and any cross-provider comparison.
3. **`usage` differs.** CLM reports `input_tokens` as encoder tokens spent on cache **misses** and adds
   `billing_units` (number of questions). There is **no `output_tokens`**, since nothing is generated.
4. **Context is 2048 tokens, not 64k** — and by default a longer state keeps the **first** 2048, while the
   MLX port notes upstream trained the heads on the *last* tokens of long states. So the default
   truncation direction may be the wrong one for this model (`Engine(..., truncation="tail")` exists to
   correct it), and our 32k/64k state assumptions do not carry over.

### The omlx artifact cannot be served from omlx

`mlx-community/CLM-v0.1-8B-MLX-8bit` is an unofficial community MLX port. Unlike the Laya MXFP8 artifact,
it **does include the decision heads** (`CLM_v0.1-8B` projection heads, float32, unchanged) and ships a
`clm_mlx` package whose `Engine.answer(...)` / `Engine.rank(...)` accept upstream's wire format. Its author
reports parity against the upstream server over 778 questions — 99.0% top-option agreement versus
upstream's own 98.6% self-agreement — i.e. inside upstream's noise. So it is a *working* decision model on
Apple Silicon. But:

- **It is a Python library, not an HTTP server.** There is no `/v1/systemone` route in the port. Our
  adapter can only POST to an endpoint, so something must wrap `clm_mlx` in an HTTP server first.
- **LM Studio / MLXUI cannot serve it.** The port's own README states the CLM answer/rank behavior "isn't
  one of MLXUI's built-in model protocols — using it there would need custom integration on top of the raw
  encoder". The encoder alone has `lm_head` removed, so it cannot even generate text.
- **The upstream HTTP path needs a GPU.** `clm-serve` is the CPU API layer, but it expects a **vLLM
  pooling server** for Qwen3-8B on `:8090`, and vLLM pooling is not an Apple-Silicon path.

**Implication.** On a Mac, CLM is reachable only by writing a thin HTTP wrapper around `clm_mlx` that
exposes `POST /v1/systemone` on loopback. That is a small, self-contained piece of work and the only route
by which this artifact becomes a provider. It is **not** a config change, and it is not something omlx
does for us.

## 6. Network boundary per candidate

`SelfHostedEndpointValidator` requires the literal host `127.0.0.1`, rejecting `localhost`, `0.0.0.0`,
and any other address; `ProviderPlacementValidator` forbids remote and self-hosted engines from
classifier-SPI placement. Against that:

| Candidate | Amsha lane | Loopback fit |
|---|---|---|
| TypeSafe `api.typesafe.ai` | `RemoteDecisionEngine` | HTTPS egress via `GovernedHttpClient`; needs an outbound policy entry for the host |
| Laya `laya-serve` | `SelfHostedDecisionEngine` | Client endpoint `http://127.0.0.1:8000/v1/systemone` is **accepted** — but `laya-serve` defaults to binding `0.0.0.0`, which **exposes it to the LAN**. Bind it to loopback explicitly, or accept that the separate-network-design gate applies. |
| Kev `kev-server` | `SelfHostedDecisionEngine` | Defaults to `http://127.0.0.1:3000` — fits the loopback rule as shipped. |
| CLM `clm-serve` | `SelfHostedDecisionEngine` | Client endpoint `http://127.0.0.1:8700/v1/systemone` is **accepted**, and `clm-serve` binds loopback by default. But it is a **two-process** deployment: the `:8700` API calls a `:8090` vLLM embeddings server. Both are loopback, so the letter of the rule is met — the extra internal service hop is worth noting, and the embeddings server is the part with the GPU requirement. |
| `CLM-v0.1-8B-MLX-8bit` | n/a | Library only; no endpoint. Would need a custom loopback HTTP wrapper before any lane could use it. |
| `Laya-TypedDecisions-MXFP8` | n/a | Not a provider; no endpoint. |

Both Laya and Kev ignore unknown request fields, which means Amsha's extra `decisionId`,
`schemaVersion`, and `timeoutMs` would be tolerated by them — but that tolerance is exactly what would
hide a broken mapping against the real TypeSafe endpoint. Do not use a lenient server as the
conformance reference.

## 7. Phased plan

### Phase 0 — Verify candidate facts and freeze the contract

**Status: TypeSafe contract verified; candidate protocol verified; candidate result-conformance open.**

For TypeSafe/System One, the following is confirmed from first-party documentation and recorded in §1:

- endpoint `POST https://api.typesafe.ai/v1/systemone`, `Authorization: Bearer <API_KEY>`;
- model identifiers `jev-1.13.0` with aliases `jev-latest` / `jev-preview` (the `model` field is required);
- request/response schema and all three Jev primitives (`choice`, `score`, `noul`);
- pricing $0.042/Mtok input (output free), 100K tokens/s and 40 requests/s (dynamically adjusting),
  64k context with a 32k state+longest-question budget;
- error codes `401`, `422`, `429`, and `529` (there is no documented `503`);
- no training on customer data; ZDR available on enterprise; explicit retention/residency still to be declared.

Not yet done: an account and a credentialed sandbox call. Issue the API key at
`console.typesafe.ai/keys` and set `AMSHA_DECISION_TYPESAFE_API_KEY` in the ignored local `.env`;
provider API keys use the direct environment-backed convention and do not require a `SecretVault` entry.
The retention window, training prohibition, and data-residency declarations still have to be recorded
explicitly by whoever configures and approves the provider.

For each open-weight candidate, record the exact model/revision, weight and code licenses, hardware needs,
serving runtime, protocol, and source provenance. Do not infer protocol compatibility from a model
card or a chat endpoint. Laya and Kev are verified as Apache-2.0 Jev-wire-compatible servers; see
§3–§6 for licenses, runtimes, hardware, and the loopback fit of each.

Freeze versioned Amsha decision schemas and an adapter conformance suite covering each primitive,
unknown/duplicate question IDs, malformed and partial JSON, missing confidence, timeout, overload,
authentication failure, and deterministic `NoOpinion` mapping.

### Phase 1 — TypeSafe hosted adapter, shadow-only

Implement explicit TypeSafe provider configuration and mapping through the existing remote
provider boundary. Prefer the governed transport over importing an SDK that bypasses DNS/TLS/proxy
policy. Add an adapter-specific contract test against a local HTTP fixture first, then a credentialed
sandbox smoke check using synthetic, non-sensitive decisions.

**Prerequisite — DONE (2026-10-01).** The adapter did not speak the TypeSafe contract: the existing
`RemoteDecisionEngineContractTest` fixtures encoded the same shape as the codec, so they passed while
a live call would have been rejected with `422` and two of three answer types discarded as malformed.
All four required items have now landed — see §2 "Resolution":

- a shared `SystemOneWireMapper` emitting `model`, lowercase `type`, and `instructions`, mapping
  Amsha's `criteria`/`options`/`rubricLevels` onto TypeSafe's `instructions`/`criteria` vocabulary,
  and refusing — rather than sending — a question with no valid representation;
- decoder support for the real Noul `noul` key and for Score `legend`/`probabilities` as **objects
  keyed by level index**, plus top-level `model` attribution and nested `usage` token counts;
- `529 Overloaded` mapped to `NoOpinion(OVERLOAD_REJECTED)`;
- fixtures replaced with the documented bodies, now also asserting the emitted request body.

Both the remote and self-hosted lanes share the mapper, so they cannot drift apart again. The
self-hosted lane reached parity on 2026-10-01: it recognises `529 Overloaded`, and it gained an optional
`modelId` that is **omitted** from the wire when blank — a self-hosted server picks its own checkpoint,
so echoing a hosted vendor alias such as `jev-latest` would be wrong. Verified by 131 tests across the
affected suites (`SystemOneWireMapperTest` plus both contract suites) and by a full-module re-run —
3,465 `classifier-api` + `core` tests, 0 failures, 1 skipped; `spotless:apply` clean and the module PMD
gate green.

The governance, transport, redaction, placement, transaction-guard, and no-retry behaviour was
already implemented and tested and did not need rework. What remains before a credentialed call is
the account and the explicit retention/residency declarations.

Use direct environment-backed `AMSHA_DECISION_<PROVIDER>_API_KEY` credentials, following the pattern used for other Amsha providers. Require explicit endpoint and data-governance declarations. Keep providers disabled by default; use remote credentials only through the authorized evaluation path, never from the synchronous, network-free classifier SPIs.

### Phase 2 — Open-weight/self-hosted adapter

**Status: lane at wire parity; no candidate evaluated yet.** The self-hosted lane now shares the remote
lane's mapper, recognises `529 Overloaded`, and can pin its own `modelId` (see Phase 1). What has *not*
happened is the candidate work below — no open-weight model has been run through the conformance suite,
so the protocol gate for a specific candidate is still open.

Select one candidate/use case from the Phase 0 evidence and run it through the same conformance and
evaluation suite. Start with the existing `SelfHostedDecisionEngine` only when the inference service
can be safely co-located on the required `127.0.0.1` boundary. A container/LAN/cluster endpoint is
not covered by that loopback policy; supporting one needs a separately reviewed network and
credential boundary.

If a candidate is genuinely embedded and synchronous, network-free, and payload-safe, it may use
the in-process lane. Otherwise it stays a separate-process self-hosted provider. Keep model-specific
parsing and quantization/runtime behavior in adapters, not policy code. Add further models as
conformance profiles rather than branching on model names in the router.

**Candidate note — Laya (2026-10-01).** Use the upstream `convaiinnovations/laya` package and its
`laya-serve` server, which exposes a Jev-compatible `POST /v1/systemone`. Do **not** attempt provider
integration against `sahilchachra/Laya-TypedDecisions-MXFP8`: that artifact is the ModernBERT encoder
backbone only, omits Laya's decision head, is not loadable by Laya's own API, and cannot be served by
LM Studio. It is a feature extractor, not a decision provider. See §4. Note also that `laya-serve`
binds `0.0.0.0` by default and is unauthenticated unless `LAYA_API_KEY` is set; bind it to loopback
explicitly or treat it as a separate network-boundary review.

**Candidate note — CLM (2026-10-01).** `Contrastive-LM/CLM` + `clm-serve` also exposes
`POST /v1/systemone` and is Apache-2.0, so it is a genuine second candidate. Its MLX port
(`mlx-community/CLM-v0.1-8B-MLX-8bit`) is *complete* — encoder plus decision heads, with a `clm_mlx`
`Engine` — unlike the Laya MXFP8 artifact, but it is a **Python library with no HTTP server**, so omlx
cannot serve it and a thin loopback wrapper would have to be written. Two caveats before adopting it:
`state` must be **prose, never JSON** (see §5 and §8), and CLM's `confidence` is defined differently
from TypeSafe's, so thresholds do not transfer between them. CLM also returns **`502`** when its
embeddings server is unreachable. **Corrected 2026-10-01:** an earlier draft of this note said that case
was "folded into the generic failure bucket". It is not — both lanes already map `502` explicitly, to
`NoOpinion(MISSING_MODEL)` described as "model/service unavailable" (`SelfHostedDecisionEngine` groups it
with `404`; `RemoteDecisionWireCodec` groups it with `504`). The real problem is narrower and worth
recording accurately: an *upstream dependency* outage is reported as a *missing model*, so the two are
indistinguishable to any consumer. Settling that is a semantics decision to take when a provider is
actually chosen, not something to change now — nothing consumes these reasons while the plane is
Deferred.

### Phase 3 — Comparative evaluation and evidence

Run deterministic baseline, existing structured-LLM baseline, TypeSafe, and each self-hosted
candidate on identical versioned, redacted partitions. Measure at least:

- false specialist/direct-routing rate on ambiguous and adversarial prompts;
- decision accuracy by primitive, abstention rate, and confidence calibration where confidence is
  actually supplied;
- disagreement and malformed-result rates;
- p50/p95 latency, timeout/overload rate, and cost per 1,000 decisions;
- retention/training/data-location compliance and operator recovery behavior.

Keep candidate-evidence and cost-ceiling approvals false until results are reviewed, ceilings are
recorded, and a named rollout/rollback owner is accountable. Store only payload-free aggregate
evidence in logs and dashboards.

### Phase 4 — Controlled activation, only after approval

Proceed from shadow comparison to human-reviewed advisory use, then consider a narrowly scoped,
low-risk routing pilot. Require an explicit kill switch, deterministic fallback, drift monitoring,
rollback rehearsal, and re-review when provider/model/schema versions change. Never let a model
relax existing blocks, HITL requirements, or deterministic safety policy.

## 8. Provider selection from configuration (requirement, 2026-10-01)

**Requested:** the decision provider must be switchable from `.env`, the same way the LLM slots already
are, so that a closed provider (TypeSafe) and any local provider — CLM, Laya, Kev, an omlx/LM Studio
endpoint, or something not yet written — are interchangeable without a code change.

**Status (2026-10-01): the configuration surface and the wire profile have landed; the engine wiring has
not, deliberately.**

**Scope of "interchangeable" (added 2026-10-01).** That phrase is the *requirement*, not an achievement, and it
holds only at the configuration surface — not at the credential or engine layer. No credentialed TypeSafe call
has been made, and no candidate runtime has been conformance-tested against the mapper. The auth paths are not
interchangeable either: the remote lane resolves credentials through its vault-backed governed transport, while
the self-hosted lane uses a plain HTTP client and sends its own `X-Amsha-Loopback-Token` header. A secured Laya
or CLM deployment that expects `Authorization: Bearer` therefore cannot use this adapter as documented.
`DecisionProviderConfig.Slot.apiKey` is carried and checked for presence, but nothing maps it onto either
engine's credentials.

| Piece | State |
|---|---|
| `WireProfile` (`JSON_OBJECT` \| `PROSE`) and `ProseStateRenderer` | **implemented.** The mapper takes a profile, and the self-hosted engine passes its endpoint's own. This closes the CLM blocker in §5 rather than only documenting it. |
| `DecisionProviderConfig` (`@ConfigMapping`, prefix `amsha.decision.providers`) | **implemented.** `selected` plus declared `slots`, bridged to `AMSHA_DECISION_*` names in `application.yaml` and documented in the repository `.env.example`. Each slot carries its own `model` — the endpoint and the model are separate axes, mirroring `amsha.llm.<provider>.model`. |
| `DecisionProviderSelector` | **implemented** as a pure function. It resolves the selected slot into a validated lane or a payload-free refusal, derives the lane from the endpoint host, and never resolves a credential. It also refuses an enabled slot with no `model`, and a CLM model declared without `WIRE_PROFILE=PROSE`. |
| The mapping actually binding | **container-verified.** `DecisionProviderConfigBindingTest` binds a real `@TestProfile` through Quarkus and asserts the conversions (`PT7S` → `Duration.ofSeconds(7)`, `PROSE` → the enum constant) rather than trusting that `@ConfigMapping` works. It also asserts that two slots carry *their own* `model` and do not inherit each other's. |
| Selecting a provider into a running `DecisionEngine` | **not implemented.** This is the ADR change; the plane stays Deferred. |

The prefix is `amsha.decision.providers` rather than `amsha.decision` so that the existing
`amsha.decision.shadow.*` switches do not look like unknown members of this mapping.

One trap made the binding test non-obvious: `app/src/test/resources/application.yaml` **replaces** the main
`application.yaml` on the test classpath, so no `@QuarkusTest` sees the main file's
`amsha.decision.providers.*` block at all. A test that injected the mapping without its own `@TestProfile`
would have failed to inject — and, worse, a test that asserted on a *defaulted* value would have passed
while proving nothing. The binding is therefore asserted against a profile that declares the values, not
against the shipped defaults.

A second trap is worth recording, because it silently discards work. `app/src/main/resources/env-example`
is **not** a file to edit: `app/pom.xml` has an execution `sync-env-example` that copies the repository
`.env.example` over it at the `generate-resources` phase with `overwrite=true`, and `.gitignore` excludes
it. It is a generated artifact. An edit made there is destroyed by the next build and leaves no trace in
`git status` — which is exactly what happened to an earlier draft of this section. The source of truth,
and the only file to change, is the repository `.env.example` at the root, which is what the ADR
convention ("every `@ConfigProperty` must appear in `application.yaml`, `.env.example`, and the module
README") already implies.

### The pattern to mirror

The LLM slots already solve this:

| Existing LLM-slot mechanism | Value |
|---|---|
| `AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS` | `groq,ollama` — comma-separated provider order |
| `AMSHA_CLASSIFIER_{PROVIDER}_{KEY}` | per-provider model, base URL, API key, retries |
| `generic-openai` | a catch-all slot for "self-hosted proxies, or any OpenAI API-compatible service" |

The decision-plane equivalent is `AMSHA_DECISION_PROVIDER=typesafe|clm|laya|kev|omlx|…` plus
`AMSHA_DECISION_{PROVIDER}_{ENDPOINT,MODEL,API_KEY,TIMEOUT,WIRE_PROFILE}`.

**Two axes, and the naming has to keep them apart (corrected 2026-10-01).** The provider id names the
System One *endpoint* — the thing that receives `POST /v1/systemone` — exactly as an LLM provider id
names the endpoint that receives `/v1/chat/completions`. The `model` field names what is served *there*,
exactly as `amsha.llm.<provider>.model` does. So an id may legitimately name a specific decision server
(`laya`, `kev`, `clm`), a generic local runtime host that serves whichever decision model is loaded into
it (`omlx`), or a hosted API (`typesafe`): `omlx` is a *runtime*, `laya` is a *model* it can serve, and
the same runtime slot serves either one by changing `model`.

An earlier draft of this section conflated the two, and it is worth recording why, because the mistake
was not careless — it was invisible. Its slot names were taken from the candidate matrix in §5, where
every candidate happened to be both an implementation *and* a distinct endpoint, so "candidate" and
"endpoint" coincided. The conflation only surfaced when `omlx` arrived as a runtime rather than an
implementation. The tell was a field that is redundant for some slots and essential for others:
`MODEL_ID` says nothing for `laya` (the slot name already names the model) but is unavoidable for
`omlx`. A field redundant for some keys and essential for others means the *key* is wrong.

Three defects followed, all now fixed: the axis was never stated, which made the slot list read as a
category error; the field was named `model-id` / `AMSHA_DECISION_{PROVIDER}_MODEL_ID` instead of the
repository's `model` / `AMSHA_{PROVIDER}_MODEL`; and `wire-profile` was documented as a property of the
*provider* when it is a property of the *model* — CLM's heads were trained on prose, so attaching the
dialect to the endpoint rather than to the model invites exactly the silent degradation described in §5.
The selector now refuses an enabled slot that names no model (the System One contract makes `model`
mandatory, so an enabled slot without one is refused rather than defaulted) and refuses a CLM model
declared without `WIRE_PROFILE=PROSE`, rather than sending either.

### Two constraints that make this harder than it looks

1. **A selector alone is not enough — the wire mapping must vary by provider.** TypeSafe and CLM share the
   `POST /v1/systemone` route, but `state` is a JSON string for one and **prose, never JSON**, for the
   other, and their `confidence` definitions are not comparable. One shared `SystemOneWireMapper`
   therefore needs per-provider *profiles*, or the selector will silently emit semantically wrong
   payloads. See §5.
2. **Adding configuration must not resolve an engine.** A `@ConfigMapping` for decision providers is
   harmless on its own. Wiring it into a bean that returns a working `DecisionEngine` *is* the ADR change,
   and must remain a deliberate, reviewed step with the Phase 3 evidence and Phase 4 gates behind it. The
   selector may land while the plane stays inert; it must not be the thing that quietly lights it.

### Wire profiles

Constraint 1 is addressed by an explicit, closed `WireProfile` on the self-hosted provider config:

| Profile | `state` rendering | Providers |
|---|---|---|
| `JSON_OBJECT` (default) | the projected JSON object, as a JSON string | TypeSafe Jev, Laya, Kev |
| `PROSE` | objects as `key: value` lines, arrays as `- item` lines, two-space nesting | CLM |

The prose rendering is implemented in `ProseStateRenderer` and runs strictly *after* allow-list
projection and redaction, so it cannot widen what leaves the process. A provider whose dialect is not
one of these two must fail closed rather than be coerced into the nearer profile.

### Credentials

Decision provider keys use direct environment-backed `AMSHA_DECISION_<ID>_API_KEY` values, following the convention used by other Amsha providers. The selector and status endpoint expose only whether a key is configured. Evaluation copies the configured key into request-owned bytes and clears the byte array after the request. The source configuration `String` remains resident for the process lifetime; provider configuration may retain an immutable `String` while the transient engine is alive. The former `_API_KEY_REF` name is unsupported.

## 9. Decision gates

| Gate | Required evidence | Outcome if unmet |
|---|---|---|
| Protocol | First-party or fixture-backed wire contract; all conformance cases pass | Keep candidate out of the provider registry |
| Governance | Approved retention, training use, residency, isolation, secrets, and egress policy | No production state leaves Amsha |
| Quality | Representative comparative evaluation and calibrated thresholds | Shadow-only; no routing authority |
| Operations | Latency/cost ceilings, monitoring, kill switch, named owner, rollback rehearsal | Activation approvals stay false |

## 10. Current implementation boundary

- `RemoteDecisionEngine` and `SelfHostedDecisionEngine` now **do** speak the documented
  `POST /v1/systemone` contract: both delegate to the shared `SystemOneWireMapper`, both map
  `529 Overloaded`, and the decoder accepts all three answer shapes. The earlier divergence — and the
  self-consistent fixtures that hid it — is recorded in §2. What is still missing is not code but
  evidence: no comparative corpus has been run and retention/residency remain undeclared. (A first live
  call was recorded on 2026-10-09 — see §11 — but one smoke evaluation is not gate evidence.) Note also
  that `console.typesafe.ai` is the account console, not the API host.
- `SelfHostedDecisionEngine` remains a separate-process seam with strict loopback restrictions (the
  literal `127.0.0.1`), not a general cluster-serving integration. It accepts an optional `modelId`,
  omitted from the wire when blank, and a `WireProfile` selecting the `state` dialect (§8).
- The accepted W4 evaluation path consumes provider configuration for explicit, internal-only
  evaluation requests when its default-off switch is enabled. It creates a transient engine and
  registers no `DecisionEngine` bean, so provider configuration still does not route agent traffic.
  A first live TypeSafe evaluation was recorded on 2026-10-09 — a smoke result, not the versioned-corpus
  evidence the Quality gate requires; §11 carries the detail.
- The ADR remains **Deferred**. This document does not approve enabling router flags or fabricating
  candidate evidence, cost approval, or rollout ownership.

Nothing here authorizes enabling the router flags, fabricating candidate evidence, recording cost
approval, or naming a rollout owner. Those remain unmet, and the ADR remains **Deferred**.

## 11. Status update (2026-10-09) — evaluation path implemented; production routing remains Deferred

**Credential configuration update (2026-10-09):** decision slots use direct `AMSHA_DECISION_<ID>_API_KEY` values; `_API_KEY_REF` is unsupported. `SecretVault` remains for governed transport proxy and custom-CA material. A live TypeSafe evaluation requires a rebuilt and restarted native binary.

The accepted W4 evaluation path is implemented. It permits credentialed requests only through the default-off, internal-only evaluation endpoint, constructs a transient engine, and registers no production CDI `DecisionEngine` bean. It does not route agent traffic. **A first live TypeSafe evaluation was recorded on 2026-10-09** — HTTP 200 in ~354 ms for one synthetic intent, against a rebuilt native binary. That is a smoke result, not the versioned-corpus evidence the Quality gate requires, so measured provider evidence and production promotion approvals remain open.

- **`docs/research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md`** specifies a production-grade,
  two-capability design: an **explicit evaluation path** (a shared `DecisionEngineFactory`, an
  opt-in internal-only `DecisionEvaluationService`, and an internal admin resource) that makes a real
  credentialed provider call while registering **no** CDI `DecisionEngine` bean, followed by a gated
  **production routing** activation that remains behind the ADR §8.4 promotion steps.
- **`ADR-jev-decision-plane-evaluation-path.md`** (**Accepted**, 2026-10-07) authorizes that evaluation
  path and nothing more. It does not move the parent ADR off `Deferred` and does not satisfy §8.4 steps 1–5.
- The 2026-10-09 change replaces the former `API_KEY_REF` proposal with direct `AMSHA_DECISION_<ID>_API_KEY` configuration. The evaluation service consumes it and clears its request-owned byte copy; the source config String remains resident for the process lifetime.
- The self-hosted lane sends `X-Amsha-Loopback-Token`, not `Authorization: Bearer`; use it only with a local endpoint configured to accept that header.
- **M4 is reconciled** in the parent ADR §8.3 as a lane-specific budget: p95 < 50 ms for an
  in-process/self-hosted local lane, p95 < 500 ms for a remote hosted lane. The earlier single
  "< 50 ms" figure was written for the local intent pilot and is unreachable for a hosted provider.

Nothing in this update changes any gate's status. Quality remains UNMET until M1/M2 are measured;
Governance remains UNMET until retention and residency are declared; Operations remains UNMET until
ceilings, drift alerting, and a named owner exist.

## Sources

First-party TypeSafe documentation, retrieved 2026-10-01:

- [Introduction](https://docs.typesafe.ai/) and [Quick start](https://docs.typesafe.ai/introduction/quickstart)
- [Models](https://docs.typesafe.ai/models) — model IDs, aliases, pricing, rate limits, context, data handling
- [API reference](https://docs.typesafe.ai/api) — request/response schema, question and answer types, errors
- [Legal](https://docs.typesafe.ai/legal) — DPA, MCA, privacy policy, ZDR
- [System One announcement](https://typesafe.ai/blog/introducing-system-one-models-and-jev)

Re-verified 2026-10-01 against `/api`, `/models`, `/introduction`, and `/concepts/system-one`; see §1
"Re-verified against the official documentation" for the three details that changed.

Third-party mirrors (`typesafe-jev.com`, `jevapi.dev`, `jevmodel.org`) paraphrase this contract and are
**not** authoritative. One such mirror states the rate limits as 250k tokens/s + 1200 req/min, which
contradicts the official `/models` page — cite `docs.typesafe.ai` instead.

Candidate sources, retrieved 2026-10-01:

- [sahilchachra/Laya-TypedDecisions-MXFP8](https://huggingface.co/sahilchachra/Laya-TypedDecisions-MXFP8)
  — encoder-only scope, quantization caveats, LM Studio not applicable
- [convaiinnovations/laya](https://huggingface.co/convaiinnovations/laya) — Apache-2.0, checkpoints,
  `laya-serve` Jev-compatible endpoint, benchmarks and honest limits
- [Laya project site](https://laya.convaiinnovations.com/)
- [arjun988/Kev](https://github.com/arjun988/Kev) — Apache-2.0, BYO backend, `POST /v1/systemone`
- [chaitin/Decis](https://github.com/chaitin/Decis) — self-hostable Jev-compatible inference server
- [Contrastive-LM/CLM-v0.1-8B](https://huggingface.co/Contrastive-LM/CLM-v0.1-8B) — Apache-2.0;
  contrastive System One model (frozen Qwen3-8B encoder + state/action projection heads); no generation
- [github.com/Contrastive-LM/CLM](https://github.com/Contrastive-LM/CLM) — API reference: `clm-serve`
  exposes `POST /v1/systemone` on `:8700`, in a two-process deployment with a vLLM embeddings server
- [mlx-community/CLM-v0.1-8B-MLX-8bit](https://huggingface.co/mlx-community/CLM-v0.1-8B-MLX-8bit) —
  community MLX port; **includes the decision heads** and a `clm_mlx` library, but exposes no HTTP server

Repository artifacts:

- [Jev/System One scout for Amsha](Jev-Amsha-Decision-Plane-Scout-2026.md), especially the adapter
  comparison, self-hosted-provider constraints, and its first-party-source links.
- [ADR: Jev Decision Plane](../adr/architecture/ADR-jev-decision-plane.md), especially §§3–4 and
  §§7.5–7.7.
- [ADR: Jev Decision Plane — Explicit Evaluation Path](../adr/architecture/ADR-jev-decision-plane-evaluation-path.md)
  (**Accepted**, 2026-10-07) — the opt-in, non-routing evaluation path (§11).
- [Jev Decision Plane — Production Activation Plan](Jev-Decision-Plane-Activation-Plan-2026-10-07.md) —
  the workstream-level implementation brief (§11).
- [`RemoteDecisionEngine`](../../core/src/main/java/com/amsha/core/classifier/remote/RemoteDecisionEngine.java),
  [`RemoteDecisionWireCodec`](../../core/src/main/java/com/amsha/core/classifier/remote/RemoteDecisionWireCodec.java),
  [`RemoteProviderConfig`](../../core/src/main/java/com/amsha/core/classifier/remote/RemoteProviderConfig.java)
- [`SelfHostedDecisionEngine`](../../core/src/main/java/com/amsha/core/classifier/selfhosted/SelfHostedDecisionEngine.java),
  [`SelfHostedEndpointValidator`](../../core/src/main/java/com/amsha/core/classifier/selfhosted/SelfHostedEndpointValidator.java)
- [`SystemOneWireMapper`](../../core/src/main/java/com/amsha/core/classifier/wire/SystemOneWireMapper.java) —
  the shared System One request mapper used by both the remote and self-hosted lanes
- [`DecisionPayloadDecoder`](../../classifier-api/src/main/java/com/amsha/classifier/api/DecisionPayloadDecoder.java),
  [`ProviderPayloadParser`](../../classifier-api/src/main/java/com/amsha/classifier/api/ProviderPayloadParser.java),
  [`ProviderPlacementValidator`](../../classifier-api/src/main/java/com/amsha/classifier/api/ProviderPlacementValidator.java)
- [`RemoteDecisionEngineContractTest`](../../core/src/test/java/com/amsha/core/classifier/remote/RemoteDecisionEngineContractTest.java)
