package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the single strict JSON-Schema capability-resolution contract.
 * All model paths must resolve through these fail-closed rules.
 */
class StructuredOutputCapabilitiesTest {

    private static ChatModel model(Capability... capabilities) {
        return new ChatModel() {
            @Override
            public Set<Capability> supportedCapabilities() {
                return Set.of(capabilities);
            }
        };
    }

    private static final String NORMAL_URL = "https://api.groq.com/openai/v1";
    private static final String HEADROOM_URL = "http://127.0.0.1:8787/v1";

    @Test
    void nonNativeModes_neverResolveToStrictSchema() {
        for (StructuredOutputMode mode :
                new StructuredOutputMode[] {StructuredOutputMode.PROMPT_DIRECTIVE, StructuredOutputMode.LENIENT}) {
            assertFalse(
                    StructuredOutputCapabilities.resolvesToStrictSchema(mode, true, NORMAL_URL, true), "mode=" + mode);
        }
    }

    @Test
    void toolCallContexts_neverResolveToStrictSchema() {
        assertFalse(StructuredOutputCapabilities.resolvesToStrictSchema(
                StructuredOutputMode.NATIVE, true, NORMAL_URL, false));
    }

    @Test
    void proxyRoutedUrls_neverResolveToStrictSchema_evenWhenAdvertised() {
        assertFalse(StructuredOutputCapabilities.resolvesToStrictSchema(
                StructuredOutputMode.NATIVE, true, HEADROOM_URL, true));
        assertTrue(StructuredOutputCapabilities.isHeadroomProxyUrl("https://headroom.local:8789/v1"));
        assertTrue(StructuredOutputCapabilities.isHeadroomProxyUrl("HTTP://PROXY-HEADROOM:8080"));
        assertTrue(StructuredOutputCapabilities.isHeadroomProxyUrl("http://localhost:8795/v1"));
        assertTrue(
                StructuredOutputCapabilities.isHeadroomProxyUrl("http://[::1]:8789/v1"),
                "port after a bracketed IPv6 host is a real port");
        assertFalse(
                StructuredOutputCapabilities.isHeadroomProxyUrl("http://[2001:db8::8781]/v1"),
                "hex-looking segment inside an IPv6 literal must not match the proxy-port pattern");
        assertFalse(StructuredOutputCapabilities.isHeadroomProxyUrl(null));
        assertFalse(StructuredOutputCapabilities.isHeadroomProxyUrl(NORMAL_URL));
    }

    @Test
    void deliberateProviderAdvertisement_admitsStrictSchema() {
        assertTrue(StructuredOutputCapabilities.resolvesToStrictSchema(
                StructuredOutputMode.NATIVE, true, NORMAL_URL, true));
    }

    @Test
    void modelDeclaration_alone_failsClosed_withoutProviderAdvertisement() {
        // Declaration is informative only: for OpenAI-compatible endpoints it derives
        // from builder-time response format, not reviewed provider evidence.
        assertFalse(
                StructuredOutputCapabilities.resolvesToStrictSchema(
                        StructuredOutputMode.NATIVE, false, NORMAL_URL, true),
                "declaration without advertisement must not admit strict schema");
    }

    @Test
    void noAdvertisement_failsClosed_regardlessOfModel() {
        assertFalse(
                StructuredOutputCapabilities.resolvesToStrictSchema(
                        StructuredOutputMode.NATIVE, false, NORMAL_URL, true),
                "generic-JSON-only path must not resolve to strict schema");
    }

    @Test
    void nullSilentModels_neverDeclare() {
        assertFalse(StructuredOutputCapabilities.declaresStrictJsonSchema(null));
        assertFalse(StructuredOutputCapabilities.declaresStrictJsonSchema(new ChatModel() {
            @Override
            public Set<Capability> supportedCapabilities() {
                return null;
            }
        }));
    }

    @Test
    void intersectionOf_safeIntersectionAcrossCandidates() {
        var both =
                List.of(model(Capability.RESPONSE_FORMAT_JSON_SCHEMA), model(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        assertTrue(StructuredOutputCapabilities.intersectionOf(both).contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        var mixed = List.of(model(Capability.RESPONSE_FORMAT_JSON_SCHEMA), model());
        assertTrue(
                StructuredOutputCapabilities.intersectionOf(mixed).isEmpty(),
                "one non-capable candidate must shrink the result to empty");

        var withNullEntry = new ArrayList<ChatModel>();
        withNullEntry.add(model(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        withNullEntry.add(null);
        assertTrue(
                StructuredOutputCapabilities.intersectionOf(withNullEntry).isEmpty(),
                "null candidate entry must shrink the result to empty");

        assertTrue(StructuredOutputCapabilities.intersectionOf(List.of()).isEmpty());
    }
}
