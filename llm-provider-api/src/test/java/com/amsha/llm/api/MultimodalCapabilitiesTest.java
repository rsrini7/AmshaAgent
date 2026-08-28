package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;

import org.junit.jupiter.api.Test;

class MultimodalCapabilitiesTest {

    @Test
    void defaultCapabilityResolvesOpenAiVisionModels() {
        MultimodalCapability gpt4o = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");
        assertTrue(gpt4o.isMultimodal());
        assertTrue(gpt4o.supportsType(AttachmentType.IMAGE));
        assertFalse(gpt4o.supportsType(AttachmentType.AUDIO));
        assertFalse(gpt4o.supportsType(AttachmentType.DOCUMENT_PDF));
        assertTrue(gpt4o.supportsMimeType("image/png"));
        assertTrue(gpt4o.supportsMimeType("image/jpeg"));
        assertTrue(gpt4o.supportsInlineBase64());
        assertTrue(gpt4o.supportsMediaUrls());

        MultimodalCapability gpt35 = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-3.5-turbo");
        assertFalse(gpt35.isMultimodal());
        assertEquals(MultimodalCapability.TEXT_ONLY, gpt35);
    }

    @Test
    void defaultCapabilityResolvesGeminiModels() {
        MultimodalCapability gemini = MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.5-pro");
        assertTrue(gemini.isMultimodal());
        assertTrue(gemini.supportsType(AttachmentType.IMAGE));
        assertTrue(gemini.supportsType(AttachmentType.AUDIO));
        assertTrue(gemini.supportsType(AttachmentType.DOCUMENT_PDF));
        assertTrue(gemini.supportsMimeType("image/webp"));
        assertTrue(gemini.supportsMimeType("audio/mpeg"));
        assertTrue(gemini.supportsMimeType("application/pdf"));
        assertEquals(16, gemini.maxMediaPerRequest());

        MultimodalCapability flash = MultimodalCapabilities.defaultCapabilityForModel(null, "gemini-2.0-flash");
        assertTrue(flash.isMultimodal());
        assertTrue(flash.supportsType(AttachmentType.IMAGE));
    }

    @Test
    void defaultCapabilityResolvesAnthropicModels() {
        MultimodalCapability claude =
                MultimodalCapabilities.defaultCapabilityForModel("anthropic", "claude-3-5-sonnet");
        assertTrue(claude.isMultimodal());
        assertTrue(claude.supportsType(AttachmentType.IMAGE));
        assertFalse(claude.supportsType(AttachmentType.AUDIO));
        assertTrue(claude.supportsType(AttachmentType.DOCUMENT_PDF));
        assertTrue(claude.supportsMimeType("image/png"));
        assertTrue(claude.supportsMimeType("application/pdf"));
    }

    @Test
    void defaultCapabilityResolvesOpenWeightVisionModels() {
        MultimodalCapability llava = MultimodalCapabilities.defaultCapabilityForModel("ollama", "llava:13b");
        assertTrue(llava.isMultimodal());
        assertTrue(llava.supportsType(AttachmentType.IMAGE));
        assertFalse(llava.supportsType(AttachmentType.AUDIO));
        assertEquals(5, llava.maxMediaPerRequest());

        MultimodalCapability pixtral = MultimodalCapabilities.defaultCapabilityForModel("mistral", "pixtral-12b");
        assertTrue(pixtral.isMultimodal());

        MultimodalCapability qwenVl =
                MultimodalCapabilities.defaultCapabilityForModel("generic-openai", "qwen2-vl-72b");
        assertTrue(qwenVl.isMultimodal());

        MultimodalCapability llamaVision =
                MultimodalCapabilities.defaultCapabilityForModel("ollama", "llama-3.2-11b-vision-instruct");
        assertTrue(llamaVision.isMultimodal());
    }

    @Test
    void defaultCapabilityFailsClosedForUnknownOrBlankModels() {
        assertEquals(MultimodalCapability.TEXT_ONLY, MultimodalCapabilities.defaultCapabilityForModel(null, null));
        assertEquals(MultimodalCapability.TEXT_ONLY, MultimodalCapabilities.defaultCapabilityForModel("", "   "));
        assertEquals(
                MultimodalCapability.TEXT_ONLY,
                MultimodalCapabilities.defaultCapabilityForModel("custom", "deepseek-coder"));
        assertEquals(
                MultimodalCapability.TEXT_ONLY,
                MultimodalCapabilities.defaultCapabilityForModel("generic-openai", "llama-3-70b-instruct"));
        assertEquals(
                MultimodalCapability.TEXT_ONLY,
                MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.0-pro"));
        assertEquals(
                MultimodalCapability.TEXT_ONLY,
                MultimodalCapabilities.defaultCapabilityForModel("generic-openai", "some-unrelated-vision-text"));
    }

    @Test
    void resolveCapabilityInspectsMultimodalCapable() {
        ChatModel plainModel = new PlainStubChatModel();
        assertEquals(MultimodalCapability.TEXT_ONLY, MultimodalCapabilities.resolveCapability(plainModel));

        MultimodalCapability customCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 5000L, 2, true, false);
        ChatModel capableModel = new CapableStubChatModel(customCap);

        assertEquals(customCap, MultimodalCapabilities.resolveCapability(capableModel));
    }

    @Test
    void intersectComputesSafeIntersection() {
        assertEquals(MultimodalCapability.TEXT_ONLY, MultimodalCapabilities.intersect(null));
        assertEquals(MultimodalCapability.TEXT_ONLY, MultimodalCapabilities.intersect(List.of()));
        assertEquals(
                MultimodalCapability.TEXT_ONLY,
                MultimodalCapabilities.intersect(List.of(MultimodalCapability.TEXT_ONLY)));

        MultimodalCapability cap1 = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO),
                Set.of("image/png", "image/jpeg", "audio/mpeg"),
                20_000_000L,
                10,
                true,
                true);

        MultimodalCapability cap2 = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE, AttachmentType.DOCUMENT_PDF),
                Set.of("image/png", "image/gif", "application/pdf"),
                15_000_000L,
                5,
                true,
                false);

        MultimodalCapability intersection = MultimodalCapabilities.intersect(List.of(cap1, cap2));
        assertTrue(intersection.isMultimodal());
        assertEquals(Set.of(AttachmentType.IMAGE), intersection.supportedTypes());
        assertEquals(Set.of("image/png"), intersection.supportedMimeTypes());
        assertEquals(15_000_000L, intersection.maxFileSize());
        assertEquals(5, intersection.maxMediaPerRequest());
        assertTrue(intersection.supportsInlineBase64());
        assertFalse(intersection.supportsMediaUrls());
    }

    @Test
    void intersectReturnsTextOnlyWhenDisjoint() {
        MultimodalCapability cap1 =
                new MultimodalCapability(Set.of(AttachmentType.AUDIO), Set.of("audio/mpeg"), 1000L, 1, true, true);
        MultimodalCapability cap2 = new MultimodalCapability(
                Set.of(AttachmentType.DOCUMENT_PDF), Set.of("application/pdf"), 1000L, 1, true, true);

        assertEquals(MultimodalCapability.TEXT_ONLY, MultimodalCapabilities.intersect(List.of(cap1, cap2)));
    }

    @Test
    void intersectTreatsZeroBoundsAsUnbounded() {
        MultimodalCapability bounded =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 5_000L, 3, true, true);
        MultimodalCapability unbounded =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 0L, 0, true, true);

        MultimodalCapability intersection = MultimodalCapabilities.intersect(List.of(bounded, unbounded));

        assertEquals(5_000L, intersection.maxFileSize());
        assertEquals(3, intersection.maxMediaPerRequest());
    }

    static class PlainStubChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            return null;
        }
    }

    static class CapableStubChatModel implements ChatModel, MultimodalCapable {
        private final MultimodalCapability capability;

        CapableStubChatModel(MultimodalCapability capability) {
            this.capability = capability;
        }

        @Override
        public MultimodalCapability multimodalCapability() {
            return capability;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return null;
        }
    }
}
