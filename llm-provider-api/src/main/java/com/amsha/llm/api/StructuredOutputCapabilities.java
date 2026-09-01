package com.amsha.llm.api;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single capability-resolution contract for strict JSON-Schema structured output.
 *
 * <p>All model paths (agent reasoning via the provider seam, intent classification,
 * compaction, and composite/failover wrappers) must resolve strict-schema support
 * through this contract — never through a hardcoded provider-name list or a global
 * constant.</p>
 *
 * <p>Resolution rules (fail-closed, evaluated in order):</p>
 * <ol>
 *   <li>the configured {@link StructuredOutputMode} must be {@code NATIVE};</li>
 *   <li>the request context must be toolless (tool-call requests cannot carry a
 *       constraining response format);</li>
 *   <li>the base URL must not be a rewriting proxy route (e.g. Headroom), which
 *       strips or rewrites response formats regardless of model ability;</li>
 *   <li>strict-schema support is admitted <strong>only</strong> through a
 *       deliberate SPI advertisement ({@code supportsStrictJsonSchema()}) from
 *       the owning provider factory. Model declarations are informative only —
 *       they feed composite-wrapper intersections but never admit admission,
 *       because for OpenAI-compatible endpoints a declaration derives from the
 *       builder-time response format rather than reviewed provider evidence.
 *       Accepting generic JSON mode alone is never sufficient.</li>
 * </ol>
 */
public final class StructuredOutputCapabilities {

    /**
     * Matches Headroom-style proxy ports (878x/879x). Bracketed IPv6 literal
     * bodies are stripped first so hex-looking segments inside them (e.g.
     * {@code [2001:db8::8781]}) cannot produce false positives, while real ports
     * on IPv4/IPv6 hosts ({@code localhost:8787}, {@code [::1]:8789}) still
     * match.
     */
    private static final Pattern HEADROOM_PORT_PATTERN = Pattern.compile(":87[89]\\d");

    private static final Pattern BRACKETED_IPV6_LITERAL = Pattern.compile("\\[[0-9a-fA-F:.]+\\]");

    private StructuredOutputCapabilities() {}

    /**
     * True when the URL routes through a Headroom-style rewriting proxy that
     * cannot preserve native response formats.
     */
    public static boolean isHeadroomProxyUrl(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("headroom")) {
            return true;
        }
        String withoutIpv6Literals = BRACKETED_IPV6_LITERAL.matcher(lower).replaceAll("");
        return HEADROOM_PORT_PATTERN.matcher(withoutIpv6Literals).find();
    }

    /**
     * True when the resolved model instance itself declares strict JSON-Schema
     * response-format support. Null-safe; a null or silent model never declares.
     * Reads {@code supportedCapabilities()} exactly once (declarations may be
     * computed dynamically).
     *
     * <p><strong>Semantics note (binding for Iteration 2 / Issue 03):</strong>
     * in LangChain4j {@code 1.17.2}, {@code OpenAiChatModel} derives its declared
     * capability from the builder-time response format — a factory that starts
     * passing a JSON-Schema response format at build time silently opens native
     * admission through this declaration leg, bypassing the deliberate-evidence
     * review the SPI advertisement was designed to force. All OpenAI-compatible
     * Amsha endpoints (Groq, Cerebras, Mistral, Sarvam, Cloudflare, …) are built
     * through {@code openAiBuilder}. Declaration is therefore <em>informative
     * only</em> — it feeds composite-wrapper intersections and diagnostics but is
     * never an admission signal; {@link #resolvesToStrictSchema} admits on the
     * SPI advertisement alone.</p>
     */
    public static boolean declaresStrictJsonSchema(ChatModel resolvedModel) {
        if (resolvedModel == null) {
            return false;
        }
        Set<Capability> declared = resolvedModel.supportedCapabilities();
        return declared != null && declared.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }

    /**
     * Safe intersection of the candidates' declared capability sets, for composite
     * wrappers. Computed over full declared sets; a candidate declaring nothing (or
     * null) shrinks the result to empty — the whole wrapper then resolves as
     * non-native. Returns an immutable set.
     */
    public static Set<Capability> intersectionOf(List<ChatModel> models) {
        Set<Capability> intersection = null;
        for (ChatModel model : models) {
            // Single read per candidate: declared sets may be computed dynamically
            // by implementations, so this method must call supportedCapabilities()
            // exactly once (composition-time snapshot semantics).
            Set<Capability> entryCaps = model == null ? null : model.supportedCapabilities();
            if (entryCaps == null) {
                entryCaps = Set.of();
            }
            if (intersection == null) {
                intersection = new HashSet<>(entryCaps);
            } else {
                intersection.retainAll(entryCaps);
            }
        }
        return intersection == null ? Set.of() : Set.copyOf(intersection);
    }

    /**
     * Resolves whether a strict JSON-Schema response format may be attached to
     * requests routed to the described model.
     *
     * <p>Admission is fail-closed and gated on, in order: the configured mode
     * being {@code NATIVE}; a toolless request context; a non-proxy base URL;
     * and a deliberate SPI advertisement from the owning provider factory. The
     * model's own declaration is deliberately <em>not</em> an admission signal:
     * for OpenAI-compatible endpoints it derives from the builder-time response
     * format rather than reviewed provider evidence (see the semantics note on
     * {@link #declaresStrictJsonSchema}).</p>
     *
     * @param mode                    configured structured-output mode
     * @param advertisedByProvider    deliberate SPI advertisement from the owning
     *                                provider factory ({@code supportsStrictJsonSchema()});
     *                                necessary and sufficient among capability signals
     * @param baseUrl                 effective base URL used for proxy detection;
     *                                may be null
     * @param toollessRequestContext  whether requests on this path run without tools
     * @return true only when every fail-closed gate passes and the provider
     *         deliberately advertised strict-schema support
     */
    public static boolean resolvesToStrictSchema(
            StructuredOutputMode mode, boolean advertisedByProvider, String baseUrl, boolean toollessRequestContext) {
        if (mode != StructuredOutputMode.NATIVE) {
            return false;
        }
        if (!toollessRequestContext) {
            return false;
        }
        if (isHeadroomProxyUrl(baseUrl)) {
            return false;
        }
        return advertisedByProvider;
    }
}
