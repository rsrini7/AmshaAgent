# OMLX-Compatible System One Decision Models (2026-10-14)

## Key result

OMLX 0.7.0 did not expose `POST /v1/systemone` in the local probe recorded on that version: `GET /v1/models` listed catalog IDs, then `POST /v1/systemone` returned 404. A downloaded model appearing in the catalog did not prove that this older server could run the decision interface. See the [0.7.0 local probe record](./Jev-OMLX-Local-Models-Configuration-2026-10-13.md).

OMLX 0.7.1.dev1 adds the System One route and documents Clef OpenJev decision models. That development release is installed locally. The 2026-10-15 probe below confirms that its route exists and is authentication-gated; it supersedes the 0.7.0 route finding.

## Model options

For a smaller OMLX-native System One model, use [`Jundot/clef-flash-oQ4e`](https://huggingface.co/Jundot/clef-flash-oQ4e). It is a 9B, 4-bit MLX checkpoint with the joint decision head included, about 6.28 GB on disk, and an Apache-2.0 license. The model card says OMLX serves it with `POST /v1/systemone`.

For the OpenJev family, [`openjev/openjev-MLX-4bit`](https://huggingface.co/openjev/openjev-MLX-4bit) is a 27B, text-only, 4-bit MLX model with a 15.1 GB download. Its weights use CC BY-NC 4.0, so commercial use needs a separate license. Runtime memory exceeds checkpoint size, so available unified memory may limit either option. OMLX has a native OpenJev loader; the model card's `helper/shim.py` instructions are for its reference server, not OMLX's native path.

The small and mid-size artifacts previously tried are not equivalent:

- The [CLM MLX artifact](https://huggingface.co/mlx-community/CLM-v0.1-8B-MLX-8bit) uses CLM-specific pooling and projection code; it is not a regular chat checkpoint supported by OMLX's decision loader.
- The [Laya TypedDecisions MXFP8 artifact](https://huggingface.co/sahilchachra/Laya-TypedDecisions-MXFP8) is an encoder artifact; serving its decision head requires a separate implementation.
- `jevoma4362/EuroLLM-1.7B-Instruct-mlx-4Bit` is published by a Hugging Face account named `jevoma4362`, but its [model card](https://huggingface.co/jevoma4362/EuroLLM-1.7B-Instruct-mlx-4Bit) identifies it as EuroLLM Instruct, not an OpenJev decision model.

## Compatibility status

On the installed OMLX 0.7.1.dev1 server, the 2026-10-15 local probe returned 401 for `POST /v1/systemone` and 404 for `POST /v1`. Controls returned 404 for a definitely unknown route and 401 for `POST /v1/chat/completions`; `GET /v1/models` returned 405 to POST. This distinguishes an existing, authentication-gated System One route from an unknown path. The previous claim that 0.7.1.dev1 lacks `/v1/systemone` was incorrect.

The OMLX decision endpoint default is `http://127.0.0.1:8000/v1/systemone`. The route probe establishes that the route exists, not that Amsha's client authentication succeeds. OMLX's OpenAI surface expects Bearer authentication, while Amsha's self-hosted loopback lane sends `X-Amsha-Loopback-Token`; verify this auth contract with a real evaluation before enabling the slot. The evaluation path remains disabled by default, and this endpoint setting does not activate production routing.

## Sources

- [OMLX 0.7.1.dev1 release notes](https://github.com/jundot/omlx/releases/tag/v0.7.1.dev1)
- [OMLX 0.7.1.dev1 model discovery](https://github.com/jundot/omlx/blob/v0.7.1.dev1/omlx/model_discovery.py)
- [OMLX 0.7.1.dev1 OpenJev loader](https://github.com/jundot/omlx/blob/v0.7.1.dev1/omlx/models/openjev.py)
- [Clef-Flash 9B OMLX checkpoint](https://huggingface.co/Jundot/clef-flash-oQ4e)
- [OpenJev MLX 4-bit checkpoint](https://huggingface.co/openjev/openjev-MLX-4bit)
- [Accepted Amsha evaluation-path ADR](../adr/architecture/ADR-jev-decision-plane-evaluation-path.md)
