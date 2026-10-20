package com.amsha.core.classifier.wire;

/**
 * How a System One-compatible decision provider expects the {@code state} field of a
 * {@code POST /v1/systemone} request to be rendered.
 *
 * <p>Every candidate provider speaks the same route, the same lowercase question {@code type}
 * discriminator, and the same answer vocabulary, so a provider swap is mostly a base-URL change.
 * The {@code state} field is the one place where that is not true, which is why it is modelled
 * explicitly rather than assumed.
 *
 * <p>This is deliberately an enumerated, closed set. A provider whose dialect is not represented
 * here must fail closed rather than be coerced into the nearest profile: sending a provider bytes
 * it was not trained on degrades answers silently, which is worse than refusing to answer.
 */
public enum WireProfile {

    /**
     * The projected state is sent as a JSON string.
     *
     * <p>This is the TypeSafe Jev contract, which explicitly accepts a JSON object for
     * {@code state}, and it is also what Laya ({@code laya-serve}) and Kev ({@code kev-server})
     * accept. It is the default for the self-hosted lane because those two servers are the
     * Jev-wire-compatible self-hosted options.
     */
    JSON_OBJECT,

    /**
     * The projected state is sent as prose: objects render as {@code key: value} lines and arrays
     * as {@code - item} lines.
     *
     * <p>Required by CLM ({@code Contrastive-LM/CLM-v0.1-8B}). CLM is not a language model: it is a
     * frozen Qwen3-8B encoder with contrastive projection heads, and its heads were trained on
     * natural-language states. Upstream states the rendering rule explicitly, and a JSON blob is
     * out of distribution — it would not be rejected, it would quietly score worse. See
     * {@link ProseStateRenderer} for the implementation and
     * {@code docs/research/Jev-Provider-Integration-Plan-2026.md} §5 for the evidence.
     */
    PROSE
}
