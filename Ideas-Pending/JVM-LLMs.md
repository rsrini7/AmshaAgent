# JVM LLM Options

## Status

This is an evaluation note, not an implementation plan. The supported local
providers today are `ollama`, `omlx`, and `llamacpp`; `llamacpp` uses an
OpenAI-compatible endpoint. Jlama and GPULlama3.java are not integrated.

Provider selection is centralized in `ModelRouter` and `ChatModelProducer`.
Each supported provider has both a routing factory and an
`AgentChatModelProvider` implementation.

## Options

| Option | Fit | Notes |
| --- | --- | --- |
| llama.cpp | Supported | Use the existing `llamacpp` endpoint for local GGUF inference. |
| Ollama | Supported | Suitable when a local service is acceptable. |
| OMLX | Supported | OpenAI-compatible local endpoint. |
| Jlama | Evaluate | In-process JVM inference; requires a provider integration and performance validation. |
| GPULlama3.java | Evaluate | GPU-oriented JVM option; validate supported hardware and maintenance before adoption. |

## If adding a provider

1. Add routing and agent-reasoning provider factories in `amsha-core`.
2. Register URL detection in `LlmModelFactory` and onboarding defaults.
3. Add configuration to `application.yaml`, `.env.example`, and the relevant
   module README.
4. Keep provider/model selection compatible with `ModelRouter` persona and
   group overrides.
5. Run `./mvnw compile -q` and the LLM-provider tests. A native build also
   needs the normal reflection and run-time-initialization review.

## Deployment guidance

- Size model weights, KV cache, JVM heap, and operating-system headroom
  together; RAM capacity alone is not enough.
- Measure tokens per second, first-token latency, context limits, tool-call
  conformance, and concurrent-request behavior on the target hardware.
- Treat model downloads and remote model registries as network dependencies.
  Pre-stage weights for air-gapped deployments.
- Keep LLM calls outside transactions, regardless of provider locality.
- Validate tool-call behavior with the real coordinator prompts and guardrails;
  raw chat benchmarks are insufficient.

## Decision criteria

Adopt a new runtime only when it provides a measured benefit over the existing
local endpoints, works with the project’s JDK 25 and native-image constraints,
and preserves provider failover, governance, and structured-output behavior.
