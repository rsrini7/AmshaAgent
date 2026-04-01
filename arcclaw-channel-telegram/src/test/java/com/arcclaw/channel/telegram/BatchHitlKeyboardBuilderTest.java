package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class BatchHitlKeyboardBuilderTest {

    @Test
    void test_approve_reject_callback_parsing() {
        // Test per-tool approve with pipe
        String data = "BATCH_APPROVE|session123|github:read_file";
        Optional<BatchHitlKeyboardBuilder.BatchCallbackEvent> event = BatchHitlKeyboardBuilder.parseCallback(data);
        
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool);
        BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool approve = (BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool) event.get();
        assertEquals("session123", approve.sessionId());
        assertEquals("github:read_file", approve.toolName());

        // Test per-tool deny with pipe
        data = "BATCH_DENY|session456|sandbox:exec";
        event = BatchHitlKeyboardBuilder.parseCallback(data);
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool);
        BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool deny = (BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool) event.get();
        assertEquals("session456", deny.sessionId());
        assertEquals("sandbox:exec", deny.toolName());

        // Test approve all
        data = "BATCH_APPROVE_ALL|session789";
        event = BatchHitlKeyboardBuilder.parseCallback(data);
        assertTrue(event.isPresent());
        assertTrue(event.get() instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveAll);
        assertEquals("session789", ((BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveAll) event.get()).sessionId());

        // Test invalid data
        data = "BATCH_INVALID|session";
        event = BatchHitlKeyboardBuilder.parseCallback(data);
        assertTrue(event.isEmpty());
    }
}
