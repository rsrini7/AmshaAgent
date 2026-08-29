package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.MultimodalCapability;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ModelRouteSelectorTest {

    private ModelRoute route(String id, AttachmentType... types) {
        MultimodalCapability capability = types.length == 0
                ? MultimodalCapability.TEXT_ONLY
                : new MultimodalCapability(Set.of(types), Set.of(), 0L, 0, true, true);
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), 1);
    }

    @Test
    void imageTurnSelectsVisionRoute() {
        ModelRoute qwen = route("qwen", AttachmentType.IMAGE);
        ModelRoute nemotron = route("nemotron", AttachmentType.AUDIO);
        ModelRoute text = route("text");

        TurnRequirements requirements = new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, false, false);

        Optional<ModelRoute> selected =
                new DefaultModelRouteSelector().select("researcher", requirements, List.of(qwen, nemotron, text));

        assertTrue(selected.isPresent());
        assertEquals("qwen", selected.get().routeId());
        assertTrue(selected.get().fallbackRoutes().isEmpty());
    }

    @Test
    void audioTurnSelectsVoiceRoute() {
        ModelRoute qwen = route("qwen", AttachmentType.IMAGE);
        ModelRoute nemotron = route("nemotron", AttachmentType.AUDIO);

        TurnRequirements requirements = new TurnRequirements(Set.of(AttachmentType.AUDIO), Set.of(), 0L, false, false);

        Optional<ModelRoute> selected =
                new DefaultModelRouteSelector().select("researcher", requirements, List.of(qwen, nemotron));

        assertTrue(selected.isPresent());
        assertEquals("nemotron", selected.get().routeId());
    }

    @Test
    void mixedModalityWithoutSingleModelDegradesEmpty() {
        ModelRoute qwen = route("qwen", AttachmentType.IMAGE);
        ModelRoute nemotron = route("nemotron", AttachmentType.AUDIO);

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO), Set.of(), 0L, false, false);

        Optional<ModelRoute> selected =
                new DefaultModelRouteSelector().select("researcher", requirements, List.of(qwen, nemotron));

        assertTrue(selected.isEmpty());
    }

    @Test
    void textOnlyRequirementsMatchAnyRoute() {
        ModelRoute qwen = route("qwen", AttachmentType.IMAGE);

        Optional<ModelRoute> selected =
                new DefaultModelRouteSelector().select("researcher", TurnRequirements.textOnly(), List.of(qwen));

        assertTrue(selected.isPresent());
        assertEquals("qwen", selected.get().routeId());
    }

    @Test
    void geminiCoversMixedModalities() {
        ModelRoute gemini = route("gemini", AttachmentType.IMAGE, AttachmentType.AUDIO, AttachmentType.DOCUMENT_PDF);
        ModelRoute qwen = route("qwen", AttachmentType.IMAGE);

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO), Set.of(), 0L, false, false);

        Optional<ModelRoute> selected =
                new DefaultModelRouteSelector().select("researcher", requirements, List.of(qwen, gemini));

        assertTrue(selected.isPresent());
        assertEquals("gemini", selected.get().routeId());
    }

    @Test
    void textOnlyTurnPrefersTextOnlyRouteOverMultimodal() {
        ModelRoute qwen = route("qwen", AttachmentType.IMAGE);
        ModelRoute text = route("text");

        Optional<ModelRoute> selected =
                new DefaultModelRouteSelector().select("researcher", TurnRequirements.textOnly(), List.of(qwen, text));

        assertTrue(selected.isPresent());
        assertEquals("text", selected.get().routeId());
    }

    @Test
    void broaderMultimodalModelIsValidFallbackForSingleModalityPrimary() {
        ModelRoute qwenPrimary = route("qwen-primary", AttachmentType.IMAGE);
        ModelRoute geminiFallback = route("gemini-fallback", AttachmentType.IMAGE, AttachmentType.AUDIO);

        TurnRequirements requirements = new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, false, false);

        Optional<ModelRoute> selected = new DefaultModelRouteSelector()
                .select("researcher", requirements, List.of(qwenPrimary, geminiFallback));

        assertTrue(selected.isPresent());
        assertEquals("qwen-primary", selected.get().routeId());
        assertEquals(1, selected.get().fallbackRoutes().size());
        assertEquals(
                "gemini-fallback", selected.get().fallbackRoutes().getFirst().routeId());
    }

    @Test
    void sameModalityFallbacksAreAssembled() {
        ModelRoute qwenPrimary = route("qwen-primary", AttachmentType.IMAGE);
        ModelRoute qwenFallback = route("qwen-fallback", AttachmentType.IMAGE);
        ModelRoute nemotron = route("nemotron", AttachmentType.AUDIO);

        TurnRequirements requirements = new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, false, false);

        Optional<ModelRoute> selected = new DefaultModelRouteSelector()
                .select("researcher", requirements, List.of(qwenPrimary, qwenFallback, nemotron));

        assertTrue(selected.isPresent());
        assertEquals("qwen-primary", selected.get().routeId());
        assertEquals(1, selected.get().fallbackRoutes().size());
        assertEquals("qwen-fallback", selected.get().fallbackRoutes().getFirst().routeId());
    }

    @Test
    void fallbackListIsImmutable() {
        ModelRoute primary = route("primary", AttachmentType.IMAGE);
        ModelRoute fallback = route("fallback", AttachmentType.IMAGE);
        ModelRoute selected = new DefaultModelRouteSelector()
                .select(
                        "researcher",
                        new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, false, false),
                        List.of(primary, fallback))
                .orElseThrow();

        assertEquals(1, selected.fallbackRoutes().size());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> selected.fallbackRoutes().add(primary));
    }
}
