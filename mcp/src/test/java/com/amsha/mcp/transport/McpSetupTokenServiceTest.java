package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.amsha.mcp.config.McpToolAllowlist;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpSetupTokenServiceTest {

    private McpSetupTokenService service;

    @BeforeEach
    void setUp() {
        service = new McpSetupTokenService();
        McpToolAllowlist allowlist = mock(McpToolAllowlist.class);
        when(allowlist.setupTokenSecret()).thenReturn(Optional.of("test-secret"));
        service.allowlist = allowlist;
        service.initialize();
    }

    @Test
    void issueAndRedeemBindsTokenAndConsumesIt() {
        String token = service.issue("user-1", "telegram", "chat-1", "server-1");

        McpSetupTokenService.SetupGrant grant =
                service.redeem(token, "user-1", "telegram", "chat-1").orElseThrow();

        assertEquals("user-1", grant.userId());
        assertEquals("server-1", grant.serverName());
        assertEquals(McpSetupTokenService.SetupMode.ONBOARD, grant.mode());
        assertTrue(service.redeem(token, "user-1").isEmpty());
    }

    @Test
    void editTokenCarriesEditMode() {
        String token = service.issueForEdit("user-1", "chat", "chat-1", "server-1");

        McpSetupTokenService.SetupGrant grant =
                service.redeem(token, "user-1", "chat", "chat-1").orElseThrow();

        assertEquals(McpSetupTokenService.SetupMode.EDIT, grant.mode());
    }

    @Test
    void channelMismatchDoesNotConsumeToken() {
        String token = service.issue("user-1", "telegram", "chat-1", "server-1");

        assertTrue(service.redeem(token, "user-1", "telegram", "other-chat").isEmpty());
        assertTrue(service.redeem(token, "user-1", "telegram", "chat-1").isPresent());
    }

    @Test
    void invalidIssueAndRedeemInputsFailClosed() {
        assertTrue(service.redeem("", "user-1").isEmpty());
        assertTrue(service.redeem("token", "").isEmpty());
        Assertions.assertThrows(
                IllegalArgumentException.class, () -> service.issue("", "telegram", "chat-1", "server-1"));
    }
}
