package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaDownloadPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MultimodalMetricsAndOperationalTest {

    private SimpleMeterRegistry meterRegistry;
    private MediaDownloadPort downloadPort;

    private static final byte[] VALID_PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    private static final byte[] VALID_AUDIO =
            new byte[] {(byte) 0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A, 0x54, 0x45, 0x53, 0x54};

    private static final byte[] VALID_PDF = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                    + "stream\nBT /F1 12 Tf (Hello PDF Multimodal Extraction) Tj ET\nendstream\n%%EOF")
            .getBytes(StandardCharsets.ISO_8859_1);

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("type", "source", "status", "reason");
    private static final Set<String> ALLOWED_TYPES = Set.of("image", "audio", "document_pdf", "other");
    private static final Set<String> ALLOWED_SOURCES = Set.of("inline", "uri", "telegram", "unsupported", "unknown");
    private static final Set<String> ALLOWED_STATUSES = Set.of("attached", "fallback", "success", "error");
    private static final Set<String> ALLOWED_REASONS = Set.of(
            "unsupported_modality",
            "transport_unsupported",
            "oversized",
            "cumulative_exceeded",
            "count_exceeded",
            "unavailable",
            "extraction_error",
            "unknown");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        downloadPort = mock(MediaDownloadPort.class);
        when(downloadPort.maxTotalPayloadBytes()).thenReturn(50L * 1024L * 1024L);
    }

    @Test
    void attachedMediaEmitsIngestionMetricWithBoundedTags() {
        MediaAttachment imageAttachment =
                MediaAttachment.ofInline("img-1", AttachmentType.IMAGE, "image/png", "sample.png", VALID_PNG, Map.of());

        DownloadedMedia downloaded =
                DownloadedMedia.ofInline("img-1", AttachmentType.IMAGE, "image/png", "sample.png", VALID_PNG);
        when(downloadPort.downloadAll(anyList(), anyString(), anyBoolean())).thenReturn(List.of(downloaded));

        MultimodalCapability capability = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10 * 1024 * 1024L, 10, true, true);

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Analyze this image",
                List.of(imageAttachment),
                capability,
                "gpt-4o",
                downloadPort,
                "user-1",
                false,
                meterRegistry);

        assertNotNull(message);
        assertEquals(2, message.contents().size());
        assertTrue(message.contents().get(1) instanceof ImageContent);

        assertAllMetersBounded();
        assertEquals(
                1,
                meterRegistry
                        .counter(
                                "amsha.media.ingestion.total",
                                "type",
                                "image",
                                "source",
                                "inline",
                                "status",
                                "attached")
                        .count());
    }

    @Test
    void unsupportedModalityFallsBackAndEmitsMetrics() {
        MediaAttachment audioAttachment = MediaAttachment.ofInline(
                "audio-1", AttachmentType.AUDIO, "audio/mpeg", "clip.mp3", VALID_AUDIO, Map.of());

        // Capability without audio support
        MultimodalCapability textAndImageOnly = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10 * 1024 * 1024L, 10, true, true);

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Listen to this audio",
                List.of(audioAttachment),
                textAndImageOnly,
                "claude-3-5-sonnet",
                downloadPort,
                "user-1",
                false,
                meterRegistry);

        assertNotNull(message);
        assertTrue(message.contents().stream().allMatch(TextContent.class::isInstance));
        String text = message.singleText();
        assertTrue(text.contains("does not support AUDIO modality"));

        assertAllMetersBounded();
        assertEquals(
                1,
                meterRegistry
                        .counter(
                                "amsha.media.ingestion.total",
                                "type",
                                "audio",
                                "source",
                                "inline",
                                "status",
                                "fallback")
                        .count());
        assertEquals(
                1,
                meterRegistry
                        .counter("amsha.media.fallback.total", "reason", "unsupported_modality", "type", "audio")
                        .count());
    }

    @Test
    void pdfExtractionEmitsDurationAndCharMetricsOnFallback() {
        MediaAttachment pdfAttachment = MediaAttachment.ofInline(
                "pdf-1", AttachmentType.DOCUMENT_PDF, "application/pdf", "doc.pdf", VALID_PDF, Map.of());

        DownloadedMedia downloaded =
                DownloadedMedia.ofInline("pdf-1", AttachmentType.DOCUMENT_PDF, "application/pdf", "doc.pdf", VALID_PDF);
        when(downloadPort.downloadAll(anyList(), anyString(), anyBoolean())).thenReturn(List.of(downloaded));

        // Model does not natively support PDF
        MultimodalCapability noPdfCapability = MultimodalCapability.TEXT_ONLY;

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Summarize PDF",
                List.of(pdfAttachment),
                noPdfCapability,
                "gpt-4o-mini",
                downloadPort,
                "user-1",
                false,
                meterRegistry);

        assertNotNull(message);
        String text = message.singleText();
        assertTrue(text.contains("Extracted Text:"));
        assertTrue(text.contains("Hello PDF Multimodal Extraction"));

        assertAllMetersBounded();
        assertNotNull(meterRegistry.find("amsha.media.extraction.duration").timer());
        assertNotNull(meterRegistry.find("amsha.media.extraction.chars").counter());
    }

    @Test
    void oversizedAndBatchLimitsEmitFallbackMetrics() {
        byte[] oversized = new byte[1024 * 1024]; // 1 MB
        MediaAttachment bigAtt =
                MediaAttachment.ofInline("big-1", AttachmentType.IMAGE, "image/png", "big.png", oversized, Map.of());

        MultimodalCapability limitedCap = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), 512 * 1024L, 10, true, true);

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Look at this",
                List.of(bigAtt),
                limitedCap,
                "gemini-1.5-flash",
                downloadPort,
                "user-1",
                false,
                meterRegistry);

        assertNotNull(message);
        assertTrue(message.singleText().contains("Exceeds model maximum supported size"));

        assertAllMetersBounded();
        assertEquals(
                1,
                meterRegistry
                        .counter("amsha.media.fallback.total", "reason", "oversized", "type", "image")
                        .count());
    }

    private void assertAllMetersBounded() {
        List<Meter> meters = meterRegistry.getMeters();
        assertFalse(meters.isEmpty(), "Expected at least one metric to be registered");

        for (Meter meter : meters) {
            String name = meter.getId().getName();
            assertTrue(name.startsWith("amsha.media."), "Meter name must start with amsha.media: " + name);

            for (Tag tag : meter.getId().getTags()) {
                String key = tag.getKey();
                String val = tag.getValue();

                assertTrue(ALLOWED_TAG_KEYS.contains(key), "Tag key must be bounded: " + key);

                switch (key) {
                    case "type" -> assertTrue(ALLOWED_TYPES.contains(val), "Type tag must be bounded: " + val);
                    case "source" -> assertTrue(ALLOWED_SOURCES.contains(val), "Source tag must be bounded: " + val);
                    case "status" -> assertTrue(ALLOWED_STATUSES.contains(val), "Status tag must be bounded: " + val);
                    case "reason" -> assertTrue(ALLOWED_REASONS.contains(val), "Reason tag must be bounded: " + val);
                }

                // Verify zero payload/identifier leakage
                assertFalse(val.contains("user-1"), "User ID leaked in metric tag: " + val);
                assertFalse(val.contains("sample.png"), "Filename leaked in metric tag: " + val);
                assertFalse(val.contains("gpt-4o"), "Model name leaked in metric tag: " + val);
                assertFalse(val.contains("Analyze this image"), "Prompt leaked in metric tag: " + val);
            }
        }
    }
}
