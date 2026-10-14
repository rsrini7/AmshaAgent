# Jev Decision Models on Local OMLX (2026-10-13)

## Follow-up verification (2026-10-14)

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

## Recommendation

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
AMSHA_DECISION_OMLX_TIMEOUT=PT30S
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
