package com.amsha.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.ApprovalReceiptIssuer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class McpApprovalServiceTest {
    @Test
    void rejectionByAnotherUserDoesNotConsumePendingApproval() {
        ApprovalReceiptIssuer issuer = Mockito.mock(ApprovalReceiptIssuer.class);
        McpApprovalService service = new McpApprovalService(issuer);
        var pending = service.request(
                "user-1", McpApprovalService.Operation.CONNECT, "server", "https://example.test/mcp", Map.of(), null);

        assertFalse(service.reject("user-2", pending.taskId()));
        assertEquals(pending, service.find("user-1", pending.taskId()).orElseThrow());
    }

    @Test
    void listsOnlyLiveApprovalsOwnedByUser() {
        ApprovalReceiptIssuer issuer = Mockito.mock(ApprovalReceiptIssuer.class);
        McpApprovalService service = new McpApprovalService(issuer);
        var pending = service.request(
                "user-1",
                McpApprovalService.Operation.ENABLE_TOOL,
                "server",
                "https://example.test/mcp",
                Map.of(),
                "search");

        assertEquals(1, service.list("user-1").size());
        assertTrue(service.list("user-2").isEmpty());
        assertEquals(pending.taskId(), service.list("user-1").getFirst().taskId());
    }

    @Test
    void approvalIsUserBoundAndMintsTypedSingleUseReceipt() {
        ApprovalReceiptIssuer issuer = Mockito.mock(ApprovalReceiptIssuer.class);
        when(issuer.issueForExplicitApproval(
                        Mockito.eq("user-1"),
                        Mockito.anyString(),
                        Mockito.eq(OutboundPurpose.MCP_TOOL_ENABLE),
                        Mockito.eq("example.test")))
                .thenReturn("receipt-1");
        McpApprovalService service = new McpApprovalService(issuer);

        var pending = service.request(
                "user-1",
                McpApprovalService.Operation.ENABLE_TOOL,
                "server",
                "https://example.test/mcp",
                Map.of("authorization", "Bearer secret"),
                "search");

        assertThrows(IllegalArgumentException.class, () -> service.approve("user-2", pending.taskId()));
        var approved = service.approve("user-1", pending.taskId());

        assertEquals("receipt-1", approved.receiptToken());
        assertEquals("search", approved.toolName());
        verify(issuer)
                .issueForExplicitApproval("user-1", pending.taskId(), OutboundPurpose.MCP_TOOL_ENABLE, "example.test");
        assertThrows(IllegalArgumentException.class, () -> service.approve("user-1", pending.taskId()));
    }

    @Test
    void approvalCarriesExplicitCredentialClear() {
        ApprovalReceiptIssuer issuer = Mockito.mock(ApprovalReceiptIssuer.class);
        when(issuer.issueForExplicitApproval(
                        Mockito.eq("user-1"),
                        Mockito.anyString(),
                        Mockito.eq(OutboundPurpose.MCP_CONNECT),
                        Mockito.eq("example.test")))
                .thenReturn("receipt-clear");
        McpApprovalService service = new McpApprovalService(issuer);

        var pending = service.request(
                "user-1",
                McpApprovalService.Operation.RECONFIGURE,
                "server",
                "https://example.test/mcp",
                Map.of(),
                null,
                4L,
                true);
        var approved = service.approve("user-1", pending.taskId());

        assertEquals("receipt-clear", approved.receiptToken());
        assertTrue(approved.clearCredentials());
    }
}
