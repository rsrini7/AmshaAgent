package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McpSetupLinkServiceTest {
    @Test
    void absoluteBaseUrlProducesChannelClickableLink() {
        McpSetupLinkService service = new McpSetupLinkService("https://agent.example/portal/");

        assertEquals("https://agent.example/portal/#/mcp/setup?token=token-1", service.link("token-1"));
    }

    @Test
    void emptyBaseUrlKeepsLocalRelativeLink() {
        McpSetupLinkService service = new McpSetupLinkService("");

        assertEquals("/#/mcp/setup?token=token-1", service.link("token-1"));
    }
}
