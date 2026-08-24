package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LlmProviderDescriptorTest {

    @Test
    void validatesNonNullFieldsAndNormalizesState() {
        ModelEntry model = new ModelEntry("gpt-4o", "GPT-4o");
        LlmProviderDescriptor desc = new LlmProviderDescriptor(
                "openai",
                "OpenAI",
                "i18n.openai",
                "API_KEY",
                "https://api.openai.com/v1",
                true,
                false,
                List.of(model),
                Map.of("tier", "pro"));

        assertAll(
                () -> assertEquals("openai", desc.id()),
                () -> assertEquals("OpenAI", desc.displayName()),
                () -> assertEquals("i18n.openai", desc.i18nKey()),
                () -> assertEquals("API_KEY", desc.authScheme()),
                () -> assertEquals("https://api.openai.com/v1", desc.defaultBaseUrl()),
                () -> assertTrue(desc.requiresApiKey()),
                () -> assertFalse(desc.embeddingCapable()),
                () -> assertEquals(1, desc.modelCatalog().size()),
                () -> assertEquals("pro", desc.attributes().get("tier")),
                () -> assertTrue(desc.supportsModel("gpt-4o")),
                () -> assertFalse(desc.supportsModel("claude-3")),
                () -> assertFalse(desc.supportsModel(null)));
    }

    @Test
    void rejectsBlankOrNullMandatoryFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmProviderDescriptor(
                        null,
                        "Name",
                        "i18n",
                        "API_KEY",
                        "http://localhost",
                        true,
                        false,
                        List.of(new ModelEntry("m", "M")),
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmProviderDescriptor(
                        " ",
                        "Name",
                        "i18n",
                        "API_KEY",
                        "http://localhost",
                        true,
                        false,
                        List.of(new ModelEntry("m", "M")),
                        Map.of()));
    }

    @Test
    void requiresApiKeyProviderMustDeclareAtLeastOneModel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LlmProviderDescriptor("openai", "OpenAI", null, null, null, true, false, List.of(), null));
    }

    @Test
    void defaultsNullOptionalFields() {
        LlmProviderDescriptor desc =
                new LlmProviderDescriptor("ollama", null, null, null, null, false, true, null, null);

        assertAll(
                () -> assertEquals("ollama", desc.displayName()),
                () -> assertEquals("ollama", desc.i18nKey()),
                () -> assertEquals("NONE", desc.authScheme()),
                () -> assertTrue(desc.modelCatalog().isEmpty()),
                () -> assertTrue(desc.attributes().isEmpty()));
    }
}
