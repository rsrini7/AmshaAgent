package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class BatchHitlKeyboardBuilderTest {

    private static final String UUID_STR = "bbe9f4c0-44aa-4b7c-8d3e-1a2b3c4d5e6f";

    @Test
    void test_compress_decompress_roundtrip_plain_uuid() {
        String compressed = BatchHitlKeyboardBuilder.compressSessionId(UUID_STR);
        assertNotNull(compressed);
        assertEquals(22, compressed.length());
        assertNotEquals(UUID_STR, compressed);
        String decompressed = BatchHitlKeyboardBuilder.decompressSessionId(compressed);
        assertEquals(UUID_STR, decompressed);
    }

    @Test
    void test_compress_decompress_roundtrip_cron_prefix() {
        String cronSid = "cron-" + UUID_STR;
        String compressed = BatchHitlKeyboardBuilder.compressSessionId(cronSid);
        assertNotNull(compressed);
        assertTrue(compressed.startsWith("c-"));
        assertEquals(24, compressed.length());
        String decompressed = BatchHitlKeyboardBuilder.decompressSessionId(compressed);
        assertEquals(cronSid, decompressed);
    }

    @Test
    void test_compress_decompress_roundtrip_delegate_suffix() {
        String delegateSid = UUID_STR + "_delegate_shiva_1778823728364";
        String compressed = BatchHitlKeyboardBuilder.compressSessionId(delegateSid);
        assertNotNull(compressed);
        assertTrue(compressed.length() < delegateSid.length());
        assertTrue(compressed.endsWith("_delegate_shiva_1778823728364"));
        String decompressed = BatchHitlKeyboardBuilder.decompressSessionId(compressed);
        assertEquals(delegateSid, decompressed);
    }

    @Test
    void test_compress_non_uuid_passthrough() {
        String shortId = "session123";
        assertEquals(shortId, BatchHitlKeyboardBuilder.compressSessionId(shortId));
        assertEquals(shortId, BatchHitlKeyboardBuilder.decompressSessionId(shortId));
    }

    @Test
    void test_compress_null_returns_null() {
        assertNull(BatchHitlKeyboardBuilder.compressSessionId(null));
        assertNull(BatchHitlKeyboardBuilder.decompressSessionId(null));
    }

    @Test
    void test_approve_reject_callback_parsing_with_compressed_id() {
        String compressed = BatchHitlKeyboardBuilder.compressSessionId(UUID_STR);
        String data = "BATCH_APPROVE|" + compressed + "|github:read_file";
        Optional<BatchHitlKeyboardBuilder.BatchCallbackEvent> event = BatchHitlKeyboardBuilder.parseCallback(data);
        
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool);
        BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool approve = (BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool) event.get();
        assertEquals(compressed, approve.sessionId());
        assertEquals("github:read_file", approve.toolName());

        String data2 = "BATCH_DENY|" + compressed + "|sandbox:exec";
        event = BatchHitlKeyboardBuilder.parseCallback(data2);
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool);
        BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool deny = (BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool) event.get();
        assertEquals(compressed, deny.sessionId());
        assertEquals("sandbox:exec", deny.toolName());
    }

    @Test
    void test_parse_invalid_returns_empty() {
        assertTrue(BatchHitlKeyboardBuilder.parseCallback("BATCH_INVALID|session").isEmpty());
        assertTrue(BatchHitlKeyboardBuilder.parseCallback(null).isEmpty());
        assertTrue(BatchHitlKeyboardBuilder.parseCallback("garbage").isEmpty());
    }

    @Test
    void test_isUuid() {
        assertTrue(BatchHitlKeyboardBuilder.isUuid(UUID_STR));
        assertFalse(BatchHitlKeyboardBuilder.isUuid("short"));
        assertFalse(BatchHitlKeyboardBuilder.isUuid("bbe9f4c0-44aa-4b7c-8d3e-1a2b3c4d5e6")); // 35 chars
        assertFalse(BatchHitlKeyboardBuilder.isUuid("bbe9f4c0-44aa-4b7c-8d3e-1a2b3c4d5e6ff")); // 36 but no dash at 13
        assertFalse(BatchHitlKeyboardBuilder.isUuid(""));
    }

    @Test
    void test_buildKeyboard_uses_compressed_id() {
        var markup = BatchHitlKeyboardBuilder.buildKeyboard(UUID_STR, java.util.List.of("sandbox_exec"));
        var rows = markup.getInlineKeyboard();
        assertNotNull(rows);
        assertFalse(rows.isEmpty());
        String cb = rows.get(0).get(0).getCallbackData();
        assertNotNull(cb);
        assertTrue(cb.startsWith("A|"));
        assertTrue(cb.length() <= 64);
        // Verify compressed ID is 22 chars (not the original 36)
        String sessionPart = cb.split("\\|")[1];
        assertEquals(22, sessionPart.length());
        // Roundtrip: parse the callback back to original UUID
        var event = BatchHitlKeyboardBuilder.parseCallback(cb);
        assertTrue(event.isPresent());
        String decompressed = BatchHitlKeyboardBuilder.decompressSessionId(
            ((BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool) event.get()).sessionId());
        assertEquals(UUID_STR, decompressed);
    }
}
