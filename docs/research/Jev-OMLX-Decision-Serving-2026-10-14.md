# OMLX-Compatible System One Decision Models (2026-10-14)

## Key result

OMLX 0.7.0, which is running locally, does not expose `POST /v1/systemone`. The local probe recorded `GET /v1/models` listing catalog IDs, followed by `POST /v1/systemone` returning 404 with no decision model loaded. A model showing up in the downloaded catalog does not prove that OMLX can run its decision interface. See the [local probe record](./Jev-OMLX-Local-Models-Configuration-2026-10-13.md).

OMLX's `0.7.1.dev1` release adds the System One route and documents Clef and OpenJev decision models. It is the latest release listed upstream as of this research date, but it is a development release after stable 0.7.0. Upgrade to this or a later release before trying these models.

## Model options

For a smaller OMLX-native System One model, start with [`Jundot/clef-flash-oQ4e`](https://huggingface.co/Jundot/clef-flash-oQ4e). It is a 9B, 4-bit MLX checkpoint with the joint decision head included, about 6.28 GB on disk, and an Apache-2.0 license. Its model card says OMLX serves it with `POST /v1/systemone`.

If you want the OpenJev family specifically, use [`openjev/openjev-MLX-4bit`](https://huggingface.co/openjev/openjev-MLX-4bit). It is a 27B, text-only, 4-bit MLX model with a 15.1 GB download. Its weights use CC BY-NC 4.0, so commercial use needs a separate license.

Runtime memory will exceed checkpoint size, so available unified memory may limit either option. OMLX has its own native OpenJev loader and readout. The `helper/shim.py` instructions on the OpenJev model card are for its reference server; they are not needed for OMLX's native path.

## Why the earlier attempts failed

- OMLX 0.7.0 has no System One route, even though its model catalog can list downloaded model IDs.
- OMLX 0.7.1.dev1 documents Clef and OpenJev, not CLM or Laya. Its model discovery recognizes OpenJev as a Qwen3.5 checkpoint with an OpenJev model name or helper directory.
- The [CLM MLX artifact](https://huggingface.co/mlx-community/CLM-v0.1-8B-MLX-8bit) uses CLM-specific pooling/projection code; it is not a regular chat checkpoint supported by OMLX's decision loader.
- The [Laya TypedDecisions MXFP8 artifact](https://huggingface.co/sahilchachra/Laya-TypedDecisions-MXFP8) is an encoder artifact; the decision head and serving implementation are separate.
- `jevoma4362/EuroLLM-1.7B-Instruct-mlx-4Bit` is published by a Hugging Face account named `jevoma4362`, but its [model card](https://huggingface.co/jevoma4362/EuroLLM-1.7B-Instruct-mlx-4Bit) identifies it as EuroLLM Instruct, not an OpenJev decision model.

## Suggested configuration path

1. Update OMLX to 0.7.1.dev1 or a later release that retains the Clef/OpenJev loader and `/v1/systemone` route.
2. Add one of the model IDs above to OMLX. Let it detect the decision model, or select model type `decision` if the UI requires it.
3. Keep the shared OMLX OpenAI base URL as `http://127.0.0.1:8000/v1` in `.env.example` and `application.yaml`. OMLX's System One request URL on the updated server is `http://127.0.0.1:8000/v1/systemone`.
4. For Amsha's explicit evaluation provider, configure the full System One URL as the decision endpoint. The evaluation path remains opt-in, internal-only, audited-before-egress, and non-routing; it does not activate the deferred production decision plane. Check authentication-header compatibility before connecting a protected OMLX instance, because Amsha's self-hosted transport uses its loopback-token header.

I did not install the development OMLX release or call its new route, so that upstream path is source-verified but not runtime-verified in this workspace. The local OMLX 0.7.0 failure is confirmed.

## Sources

- [OMLX 0.7.1.dev1 release notes](https://github.com/jundot/omlx/releases/tag/v0.7.1.dev1)
- [OMLX 0.7.1.dev1 model discovery](https://github.com/jundot/omlx/blob/v0.7.1.dev1/omlx/model_discovery.py)
- [OMLX 0.7.1.dev1 OpenJev loader](https://github.com/jundot/omlx/blob/v0.7.1.dev1/omlx/models/openjev.py)
- [Clef-Flash 9B OMLX checkpoint](https://huggingface.co/Jundot/clef-flash-oQ4e)
- [OpenJev MLX 4-bit checkpoint](https://huggingface.co/openjev/openjev-MLX-4bit)
- [Accepted Amsha evaluation-path ADR](../adr/architecture/ADR-jev-decision-plane-evaluation-path.md)
