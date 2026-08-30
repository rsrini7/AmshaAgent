package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StrictJsonSchemaCapable;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Slab B focused tests for the deep {@link ModelRouteSelector} engine. Verifies modality selection,
 * MIME/size/count rejection, profile ordering, and fail-closed behavior.
 */
class DeepModelRouteSelectorTest {

    private final DefaultModelRouteSelector selector = new DefaultModelRouteSelector();

    // ── Test helpers ─────────────────────────────────────────────────────

    private ModelRoute route(String id, MultimodalCapability capability) {
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), 1);
    }

    private ModelRoute route(String id, AttachmentType... types) {
        MultimodalCapability capability = types.length == 0
                ? MultimodalCapability.TEXT_ONLY
                : new MultimodalCapability(Set.of(types), Set.of(), 0L, 0, true, true);
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), 1);
    }

    private ModelRoute routeWithMimes(String id, Set<AttachmentType> types, Set<String> mimes) {
        MultimodalCapability capability = new MultimodalCapability(types, mimes, 0L, 0, true, true);
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), 1);
    }

    private ModelRoute routeWithSize(String id, Set<AttachmentType> types, long maxFileSize) {
        MultimodalCapability capability =
                new MultimodalCapability(types, Set.of("image/png"), maxFileSize, 0, true, true);
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), 1);
    }

    private ModelRoute routeWithRevision(String id, MultimodalCapability capability, long revision) {
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), revision);
    }

    // ── 1. Text route preferred over vision/audio ────────────────────────

    @Test
    void textOnlyTurnPrefersTextRouteOverVisionAndAudio() {
        ModelRoute vision = route("qwen-vision", AttachmentType.IMAGE);
        ModelRoute audio = route("nemotron-audio", AttachmentType.AUDIO);
        ModelRoute text = route("text-only");

        Optional<ModelRoute> selected =
                selector.select("researcher", TurnRequirements.textOnly(), List.of(vision, audio, text));

        assertTrue(selected.isPresent());
        assertEquals("text-only", selected.get().routeId());
    }

    // ── 2. Image selection ───────────────────────────────────────────────

    @Test
    void imageTurnSelectsVisionCandidate() {
        ModelRoute vision = route("qwen-vision", AttachmentType.IMAGE);
        ModelRoute audio = route("nemotron-audio", AttachmentType.AUDIO);
        ModelRoute text = route("text-only");

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(vision, audio, text));

        assertTrue(selected.isPresent());
        assertEquals("qwen-vision", selected.get().routeId());
    }

    // ── 3. Audio selection ───────────────────────────────────────────────

    @Test
    void audioTurnSelectsAudioCandidate() {
        ModelRoute vision = route("qwen-vision", AttachmentType.IMAGE);
        ModelRoute audio = route("nemotron-audio", AttachmentType.AUDIO);

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.AUDIO), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(vision, audio));

        assertTrue(selected.isPresent());
        assertEquals("nemotron-audio", selected.get().routeId());
    }

    // ── 4. PDF selection ─────────────────────────────────────────────────

    @Test
    void pdfTurnSelectsPdfCapableCandidate() {
        ModelRoute gemini = route("gemini", AttachmentType.IMAGE, AttachmentType.AUDIO, AttachmentType.DOCUMENT_PDF);
        ModelRoute qwen = route("qwen-vision", AttachmentType.IMAGE);

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.DOCUMENT_PDF), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(qwen, gemini));

        assertTrue(selected.isPresent());
        assertEquals("gemini", selected.get().routeId());
    }

    // ── 5. Mixed modality requiring one capable model ────────────────────

    @Test
    void mixedImageAudioRequiresOneCandidateSupportingBoth() {
        ModelRoute vision = route("qwen-vision", AttachmentType.IMAGE);
        ModelRoute audio = route("nemotron-audio", AttachmentType.AUDIO);
        ModelRoute gemini = route("gemini", AttachmentType.IMAGE, AttachmentType.AUDIO);

        TurnRequirements requirements = new TurnRequirements(
                Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(vision, audio, gemini));

        assertTrue(selected.isPresent());
        assertEquals("gemini", selected.get().routeId());
    }

    @Test
    void mixedModalityWithoutSingleCandidateDegradesEmpty() {
        ModelRoute vision = route("qwen-vision", AttachmentType.IMAGE);
        ModelRoute audio = route("nemotron-audio", AttachmentType.AUDIO);

        TurnRequirements requirements = new TurnRequirements(
                Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(vision, audio));

        assertTrue(selected.isEmpty(), "No single candidate covers both IMAGE and AUDIO");
    }

    // ── 6. MIME, size, and media-count rejection ─────────────────────────

    @Test
    void mimeTypeRejectionFiltersIncompatibleCandidate() {
        ModelRoute pngOnly = routeWithMimes("png-model", Set.of(AttachmentType.IMAGE), Set.of("image/png"));
        ModelRoute jpegCapable = routeWithMimes(
                "jpeg-model", Set.of(AttachmentType.IMAGE), Set.of("image/png", "image/jpeg", "image/webp"));

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of("image/jpeg"), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(pngOnly, jpegCapable));

        assertTrue(selected.isPresent());
        assertEquals("jpeg-model", selected.get().routeId());
    }

    @Test
    void sizeRejectionFiltersSmallLimitCandidate() {
        long fiveMb = 5L * 1024L * 1024L;
        long twentyMb = 20L * 1024L * 1024L;
        long fileSizeTenMb = 10L * 1024L * 1024L;

        ModelRoute smallLimit = routeWithSize("small-model", Set.of(AttachmentType.IMAGE), fiveMb);
        ModelRoute largeLimit = routeWithSize("large-model", Set.of(AttachmentType.IMAGE), twentyMb);

        TurnRequirements requirements = new TurnRequirements(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), fileSizeTenMb, fileSizeTenMb, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(smallLimit, largeLimit));

        assertTrue(selected.isPresent());
        assertEquals("large-model", selected.get().routeId());
    }

    // ── 7. Broader but safe fallback capability ──────────────────────────

    @Test
    void broaderCapabilityModelServesAsFallback() {
        ModelRoute imagePrimary = route("qwen-primary", AttachmentType.IMAGE);
        ModelRoute geminiWider =
                route("gemini-fallback", AttachmentType.IMAGE, AttachmentType.AUDIO, AttachmentType.DOCUMENT_PDF);

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(imagePrimary, geminiWider));

        assertTrue(selected.isPresent());
        assertEquals("qwen-primary", selected.get().routeId());
        assertEquals(1, selected.get().fallbackRoutes().size());
        assertEquals(
                "gemini-fallback", selected.get().fallbackRoutes().getFirst().routeId());
    }

    // ── 8. No qualifying route ───────────────────────────────────────────

    @Test
    void noQualifyingRouteReturnsEmpty() {
        ModelRoute textOnly = route("text-only");

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(textOnly));

        assertTrue(selected.isEmpty(), "TEXT_ONLY route should not qualify for IMAGE turn");
    }

    // ── 9. Route ordering: profile revision preference ───────────────────

    @Test
    void routeOrderingPreservesInputPriority() {
        MultimodalCapability imageCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of(), 0L, 0, true, true);
        ModelRoute oldRevision = routeWithRevision("old-profile", imageCap, 1);
        ModelRoute newRevision = routeWithRevision("new-profile", imageCap, 5);

        TurnRequirements requirements =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, 0L, 0, false, false);

        // Input order is [old, new]; selector should respect the input priority ordering
        Optional<ModelRoute> selected = selector.select("researcher", requirements, List.of(oldRevision, newRevision));

        assertTrue(selected.isPresent());
        assertEquals("old-profile", selected.get().routeId());
        assertEquals(1, selected.get().fallbackRoutes().size());
        assertEquals("new-profile", selected.get().fallbackRoutes().getFirst().routeId());
    }

    // ── 10. Empty and null candidate handling ────────────────────────────

    @Test
    void emptyCandidatesReturnsEmpty() {
        Optional<ModelRoute> selected = selector.select("researcher", TurnRequirements.textOnly(), List.of());
        assertTrue(selected.isEmpty());
    }

    @Test
    void nullCandidatesReturnsEmpty() {
        Optional<ModelRoute> selected = selector.select("researcher", TurnRequirements.textOnly(), null);
        assertTrue(selected.isEmpty());
    }

    // ── 11. Narrower fallback preservation on partial modality turns ─────

    @Test
    void fallbackFilteringPreservesNarrowerValidFallback() {
        ModelRoute primaryImageAudio = route("gemini-image-audio", AttachmentType.IMAGE, AttachmentType.AUDIO);
        ModelRoute fallbackImageOnly = route("qwen-image-only", AttachmentType.IMAGE);

        TurnRequirements imageTurn =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of(), 0L, 0L, 0, false, false);

        Optional<ModelRoute> selected =
                selector.select("researcher", imageTurn, List.of(primaryImageAudio, fallbackImageOnly));

        assertTrue(selected.isPresent());
        assertEquals("gemini-image-audio", selected.get().routeId());
        assertEquals(1, selected.get().fallbackRoutes().size());
        assertEquals(
                "qwen-image-only", selected.get().fallbackRoutes().getFirst().routeId());
    }

    // ── 12. Structured output and chitchat ordering ──────────────────────

    @Test
    void orderForSelectionPrefersStructuredOutputCandidateWhenRequired() {
        ChatModel plainModel = Mockito.mock(ChatModel.class);
        ModelRoute plainRoute = new ModelRoute(
                "plain-text", "prov", "plain", null, plainModel, MultimodalCapability.TEXT_ONLY, List.of(), 1);

        abstract class SchemaChatModel implements ChatModel, StrictJsonSchemaCapable {}
        SchemaChatModel schemaModel = Mockito.mock(SchemaChatModel.class);
        Mockito.when(schemaModel.supportsStrictJsonSchema()).thenReturn(true);
        ModelRoute schemaRoute = new ModelRoute(
                "schema-text", "prov", "schema", null, schemaModel, MultimodalCapability.TEXT_ONLY, List.of(), 1);

        TurnRequirements structuredTurn = new TurnRequirements(Set.of(), Set.of(), 0L, 0L, 0, true, false);

        Optional<ModelRoute> selected = selector.select("researcher", structuredTurn, List.of(plainRoute, schemaRoute));

        assertTrue(selected.isPresent());
        assertEquals("schema-text", selected.get().routeId());
    }

    @Test
    void orderForSelectionPrefersTextOnlyForChitchat() {
        ModelRoute vision = route("vision-model", AttachmentType.IMAGE);
        ModelRoute text = route("text-model");

        TurnRequirements chitchat = TurnRequirements.fromContext(null, "hello there");
        assertTrue(chitchat.isChitchat());

        Optional<ModelRoute> selected = selector.select("researcher", chitchat, List.of(vision, text));

        assertTrue(selected.isPresent());
        assertEquals("text-model", selected.get().routeId());
    }

    @Test
    void orderForSelectionPrefersLightweightTextModelForChitchat() {
        ModelRoute heavyText = new ModelRoute(
                "heavy-text",
                "prov",
                "llama-70b",
                null,
                Mockito.mock(ChatModel.class),
                MultimodalCapability.TEXT_ONLY,
                List.of(),
                1);
        ModelRoute lightText = new ModelRoute(
                "light-text",
                "prov",
                "llama-8b-mini",
                null,
                Mockito.mock(ChatModel.class),
                MultimodalCapability.TEXT_ONLY,
                List.of(),
                1);

        TurnRequirements chitchat = TurnRequirements.fromContext(null, "how are you?");
        assertTrue(chitchat.isChitchat());

        Optional<ModelRoute> selected = selector.select("researcher", chitchat, List.of(heavyText, lightText));

        assertTrue(selected.isPresent());
        assertEquals("light-text", selected.get().routeId());
    }

    // ── 13. Media count limit enforcement ────────────────────────────────

    @Test
    void mediaCountExceedingLimitRejectsCandidate() {
        MultimodalCapability limitedCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 0L, 2, true, true);
        ModelRoute limitedRoute = new ModelRoute(
                "limited-count", "prov", "m", null, Mockito.mock(ChatModel.class), limitedCap, List.of(), 1);

        TurnRequirements reqs =
                new TurnRequirements(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 0L, 0L, 3, false, false);

        Optional<ModelRoute> selected = selector.select("researcher", reqs, List.of(limitedRoute));
        assertTrue(
                selected.isEmpty(), "Candidate with maxMediaPerRequest=2 must be rejected for turn with 3 media items");
    }
}
