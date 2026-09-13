# Amsha Classifier API Module (`amsha-classifier-api`)

Pure SPI contracts for shadow classifiers in Amsha Agent. Extracted from `amsha-core` (stream S6 of the core-subsystems extraction; see [ADR-extract-core-subsystems](../docs/adr/architecture/ADR-extract-core-subsystems.md), including the §8 deviation note).

`amsha-classifier-api` contains **zero dependencies on `amsha-core`**. The production implementations (ONNX shadow classifiers) live in `amsha-local-ml-onnx`; the dispatchers that invoke them remain in `amsha-core`.

## Core Contracts

| Type | Purpose |
|---|---|
| `ShadowClassifier` | Base SPI for pluggable shadow classifiers |
| `ToolRiskShadowClassifier` (+ `ToolRiskShadowContext`, `ToolRiskPrediction`, `ToolRiskAction`) | Pre-tool-boundary risk elevation signals (`ALLOW`/`ESCALATE`/`DENY` style actions) |
| `AnswerQualityShadowClassifier` (+ `AnswerQualityShadowContext`, `AnswerQualityPrediction`, `AnswerQualityAction`) | Post-answer quality signals; may consume one retry only before a tool boundary |
| `IntentShadowClassifier` | Intent shadow classification for routing hints |
| `PostPolicyToolContext` | Input context for post-policy tool risk evaluation |
| `ClassifierResult` | Uniform result wrapper with confidence and provenance |

## Behavioral Rules

- Shadow beans never register as active implementations; classifier controls distinguish requested from effective state.
- Optional absence or failure must preserve the deterministic decision without logging payloads.

## Downstream Usage

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-classifier-api</artifactId>
</dependency>
```
