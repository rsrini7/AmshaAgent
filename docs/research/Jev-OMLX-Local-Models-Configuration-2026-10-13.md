# Jev Decision Models on Local OMLX (2026-10-13)

## Follow-up probe on OMLX 0.7.0 (2026-10-14)

After aligning the OMLX decision endpoint default to `http://127.0.0.1:8000/v1` in
`.env.example` and `application.yaml`, I queried the authenticated OMLX catalog.
`GET /v1/models` returned HTTP 200 and listed the API model IDs
`CLM-v0.1-8B-MLX-8bit` and `Laya-TypedDecisions-MXFP8`.

I then sent one synthetic System One request to `POST /v1` for each model ID. Both
returned HTTP 404. OMLX's status screen still showed **0 requests, 0 tokens, and 0
models loaded**, confirming neither decision model was invoked. The API key and
response bodies are omitted from this note.

The ignored root `.env` currently enables evaluation but selects `typesafe`. I did
not call Amsha's evidence endpoint, since it would use that selected provider;
the two requests above were synthetic probes sent directly to local OMLX.

## Manual-load diagnosis on OMLX 0.7.1.dev1 (2026-10-14)

The later OMLX Desktop upgrade and manual load attempts distinguish three separate states: a model can be downloaded, fail to load into a runtime, or load and accept decision requests. **Download status `COMPLETED` only confirms that the files are present.** In the OMLX Model Manager, the two earlier artifacts still showed `Not loaded`:

| Model | OMLX type/status | Observed load result | Diagnosis |
|---|---|---|---|
| `sahilchachra/Laya-TypedDecisions-MXFP8` | `EMBEDDING / Not loaded` | OMLX returned HTTP 500: `Received 283 parameters not in model`. It reclaimed memory after the failure. | This is a model-parameter/architecture mismatch, not an out-of-memory event or an inference timeout. The model card describes a ModernBERT encoder artifact; the separate typed-decision head is not included. Having decision-related weights in the repository name does not make it a complete System One model. |
| `mlx-community/CLM-v0.1-8B-MLX-8bit` | `LLM / Not loaded` | A matching CLM load exception was not present in the warning/error log entries captured for this check. | The model card describes a feature-extraction encoder with `lm_head` removed and a custom `clm_mlx` engine. That is not a standard text-generation model. The observed task type and artifact format point to a runtime/loader mismatch, but the exact CLM exception remains unconfirmed. |

These manual-load outcomes were observed on OMLX Desktop `0.7.1.dev1`. The current upstream OMLX `main` System One loader lists Clef, OpenJev, and Decision 3.0 families and does not list CLM or Laya. Its decision runtime is built around a Qwen3.5 backbone. This supports the model-format diagnosis, but the upstream source links below describe `main`; they are not a version-pinned audit of the installed development build. See OMLX's [model discovery](https://github.com/jundot/omlx/blob/main/omlx/model_discovery.py), [decision runtime](https://github.com/jundot/omlx/blob/main/omlx/models/decision.py), and [API documentation](https://github.com/jundot/omlx/blob/main/README.md), along with the [CLM model card](https://huggingface.co/mlx-community/CLM-v0.1-8B-MLX-8bit) and [Laya model card](https://huggingface.co/sahilchachra/Laya-TypedDecisions-MXFP8).

By comparison, `Jundot/clef-flash-oQ4e` loaded and was invoked through Amsha's explicit, non-routing evaluation path: corpus v2 evaluated 25 in-scope cases through the OMLX provider. The aggregate is recorded in [`summary.json`](../../.scratch/decision-corpus-omlx-2026-10-14/summary.json). This proves that the updated OMLX endpoint can serve that supported decision model; it does not prove CLM or Laya compatibility.

**Triage for the next model attempt:** record the OMLX version, exact repository/model ID, Model Manager task type, download status, load status, and the single redacted load exception with its timestamp. Keep load success separate from a successful `/v1/systemone` request and from Amsha provider invocation. For CLM, capture the OMLX log entry during a fresh manual load before assigning a precise root cause. Do not treat the 50-second request timeout as a remedy for a model that fails at load time.

**Current recommendation:** stop retrying these same CLM and Laya artifacts through OMLX's built-in loader. Use an OMLX model recognized as a decision model (the tested Clef model is one option), or build a serving adapter that implements the missing model-specific loading and System One decision contract. The production Jev decision plane remains Deferred; the successful corpus call was explicit evaluation only.

## Historical recommendation for OMLX 0.7.0

Do not configure Amsha to send the two downloaded artifacts directly to the current
local OMLX server. The OMLX app reports version **0.7.0, build 2987**, listening on
`127.0.0.1:8000`; its model library contains `mlx-community/CLM-v0.1-8B-MLX-8bit`
and `sahilchachra/Laya-TypedDecisions-MXFP8`, but the status page showed **0 models
loaded**. `GET /v1/models` sent with the local key returned 200 and listed both
downloads among six cached models. A synthetic System One-shaped `POST` to the
configured base URL `http://127.0.0.1:8000/v1` returned **404**. A read-only
`GET /openapi.json` also returned 200 and confirmed that the running server has no
`/v1/systemone` route. The key value is intentionally not recorded here.

The official OMLX `v0.7.0` source does not expose `POST /v1/systemone`; it lists the
chat, completion, message, embedding, rerank, and model routes. Current upstream
`main` has since added `/v1/systemone`, but its decision-model loader supports Clef,
OpenJev, and Decision 3.0, not CLM or Laya. Thus an OMLX build with the route still
would not establish support for these two model families. See the [v0.7.0 release](https://github.com/jundot/omlx/releases/tag/v0.7.0), [v0.7.0 API list](https://github.com/jundot/omlx/blob/v0.7.0/README.md), [current System One route](https://github.com/jundot/omlx/blob/main/omlx/api/systemone_models.py), [current model discovery](https://github.com/jundot/omlx/blob/main/omlx/model_discovery.py), and [current decision engine](https://github.com/jundot/omlx/blob/main/omlx/engine/decision.py).

## The two artifacts

- **CLM MLX 8-bit** includes CLM's decision heads as an MLX library model, but it is
  not itself a System One HTTP server. OMLX's stable loader has no CLM decision
  implementation. The [official model card](https://huggingface.co/mlx-community/CLM-v0.1-8B-MLX-8bit)
  and [OMLX model discovery](https://github.com/jundot/omlx/blob/v0.7.0/omlx/model_discovery.py)
  support this distinction.
- **Laya TypedDecisions MXFP8** is a non-autoregressive, decision-specific encoder
  artifact without a chat `generate()` path. It cannot be used as an ordinary
  OpenAI-compatible chat model, and OMLX does not list its model family in its
  decision loader. See its [official model card](https://huggingface.co/sahilchachra/Laya-TypedDecisions-MXFP8).

Being present in OMLX's downloaded model library does not mean the app can load the
model's decision implementation or answer System One requests.

## Amsha configuration contract

Amsha already declares an `omlx` decision slot in `application.yaml` and `.env.example`.
Its endpoint property is the **full URL Amsha POSTs to**; Amsha does not append a
route. The OMLX base URL default is `http://127.0.0.1:8000/v1`, as requested, and
the selector accepts the explicit `127.0.0.1` host and valid port (`localhost` is
rejected). The mapper sends a System One request body to that URL. OMLX 0.7.0
returns 404 for that request because it has no System One route, so this default
does not invoke either model's decision interface. The mapper's body format
supports typed System One questions and a `state` representation selected by
`wire-profile`; OMLX's chat-completion endpoint expects a different request.
Relevant sources: [provider selection and wire-profile docs](../CONFIGURATION.md),
[endpoint selector](../../core/src/main/java/com/amsha/core/config/DecisionProviderSelector.java),
[System One mapper](../../core/src/main/java/com/amsha/core/classifier/wire/SystemOneWireMapper.java),
and [self-hosted request transport](../../core/src/main/java/com/amsha/core/classifier/selfhosted/SelfHostedDecisionEngine.java).

For self-hosted authentication, `AMSHA_DECISION_OMLX_API_KEY` becomes the custom
`X-Amsha-Loopback-Token` header; Amsha does not send `Authorization: Bearer` on this
lane. A direct OMLX endpoint must be unauthenticated locally or accept that header.

If a compatible adapter later accepts System One requests at the OMLX base URL,
the endpoint/model settings would have this shape. The current OMLX server cannot
use this configuration to run decisions:

```dotenv
AMSHA_DECISION_EVALUATION_ENABLED=true
AMSHA_DECISION_PROVIDER=omlx
AMSHA_DECISION_OMLX_ENABLED=true
AMSHA_DECISION_OMLX_ENDPOINT=http://127.0.0.1:8000/v1
AMSHA_DECISION_OMLX_MODEL=<model identifier understood by that adapter>
AMSHA_DECISION_OMLX_WIRE_PROFILE=JSON_OBJECT
AMSHA_DECISION_OMLX_TIMEOUT=PT50S
AMSHA_DECISION_OMLX_GOVERNANCE_RETENTION_WINDOW=PT0S
AMSHA_DECISION_OMLX_GOVERNANCE_ALLOW_TRAINING_USE=false
AMSHA_DECISION_OMLX_GOVERNANCE_TENANT_ISOLATION=true
AMSHA_DECISION_OMLX_GOVERNANCE_PROJECTION_ALLOW_LIST=<explicit state fields>
```

Use `PROSE` for a CLM adapter; CLM's heads were trained on prose state. The
`omlx` slot defaults to `JSON_OBJECT`, so this must be set explicitly for CLM.
An empty model is allowed only when the self-hosted server chooses its checkpoint.
The projection allow-list is mandatory. Evaluation remains default-off, internal-only,
audited, and non-routing; enabling it does not register a production `DecisionEngine`
or enable decision routing. See the [accepted evaluation-path ADR](../adr/architecture/ADR-jev-decision-plane-evaluation-path.md).

For the exact CLM/Laya downloads, use a server or adapter that explicitly supports
those MLX inference paths and exposes the System One contract. Do not assume the
upstream PyTorch serving implementations can load these MLX artifacts without
checking their runtime compatibility.

## Sources

- OMLX v0.7.0 [release](https://github.com/jundot/omlx/releases/tag/v0.7.0), [server source](https://github.com/jundot/omlx/blob/v0.7.0/omlx/server.py), [API list](https://github.com/jundot/omlx/blob/v0.7.0/README.md), and [model discovery](https://github.com/jundot/omlx/blob/v0.7.0/omlx/model_discovery.py).
- OMLX current `main`: [API route list](https://github.com/jundot/omlx/blob/main/README.md), [System One request schema](https://github.com/jundot/omlx/blob/main/omlx/api/systemone_models.py), [model discovery](https://github.com/jundot/omlx/blob/main/omlx/model_discovery.py), and [decision engine](https://github.com/jundot/omlx/blob/main/omlx/engine/decision.py).
- The [CLM MLX model card](https://huggingface.co/mlx-community/CLM-v0.1-8B-MLX-8bit) and [Laya MXFP8 model card](https://huggingface.co/sahilchachra/Laya-TypedDecisions-MXFP8).
