package com.amsha.onboarding.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.amsha.mcp.transport.McpConnectionLifecycleService;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.onboarding.spi.ValidatedOnboardingInput;
import com.amsha.tool.api.ToolCategory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpOnboardingExtensionTest {

    @Mock
    McpConnectionLifecycleService lifecycleService;

    private McpOnboardingExtension extension() {
        McpOnboardingExtension ext = new McpOnboardingExtension();
        ext.lifecycleService = lifecycleService;
        return ext;
    }

    private OnboardingContext ctx() {
        return new OnboardingContext("owner-1", "sess-1", "mcp");
    }

    @Test
    void descriptorIsOptionalExtensionStep() {
        OnboardingStepDescriptor d = extension().descriptor();
        assertEquals("mcp", d.extensionId());
        assertTrue(d.optional());
        assertEquals(3, d.fields().size());
    }

    @Test
    void prepareProducesStableFingerprint() {
        PreparedOnboardingStep prepared = extension().prepare(ctx());
        assertEquals("mcp", prepared.extensionId());
        assertNotNull(prepared.fingerprint());
        assertEquals(prepared.fingerprint(), ctx().ownerId() + ":" + ctx().extensionNamespace());
    }

    @Test
    void validateRejectsMissingFields() {
        OnboardingExtension ext = extension();
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        // missing name
        OnboardingValidationResult r1 = ext.validate(
                ctx(),
                ext.prepare(ctx()),
                new OnboardingInput("mcp", "{\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}"));
        assertFalse(r1.valid());
        // valid
        OnboardingValidationResult r2 = ext.validate(
                ctx(),
                ext.prepare(ctx()),
                new OnboardingInput(
                        "mcp", "{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NETWORK\"}"));
        assertTrue(r2.valid());
    }

    @Test
    void validateRejectsBadEndpointViaLifecycleService() {
        OnboardingExtension ext = extension();
        doThrow(new IllegalArgumentException("Invalid MCP endpoint"))
                .when(lifecycleService)
                .validateHttpRegistration(eq("bad://url"), anyMap());
        OnboardingValidationResult r = ext.validate(
                ctx(),
                ext.prepare(ctx()),
                new OnboardingInput("mcp", "{\"name\":\"svc\",\"endpoint\":\"bad://url\",\"category\":\"NETWORK\"}"));
        assertFalse(r.valid());
        assertTrue(r.safeFieldErrors().contains("Invalid MCP endpoint"));
    }

    @Test
    void validatePassesHeadersToLifecycleService() {
        OnboardingExtension ext = extension();
        Map<String, String> headers = Map.of("Authorization", "Bearer token");
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());

        OnboardingValidationResult result = ext.validate(
                ctx(),
                ext.prepare(ctx()),
                new OnboardingInput(
                        "mcp",
                        "{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\","
                                + "\"category\":\"NETWORK\",\"headers\":{\"Authorization\":\"Bearer token\"}}"));

        assertTrue(result.valid());
        verify(lifecycleService).validateHttpRegistration("https://mcp.example.com", headers);
    }

    @Test
    void validateRejectsCommaInServerNameBecauseReceiptsUseCommaDelimiter() {
        OnboardingExtension ext = extension();

        OnboardingValidationResult result = ext.validate(
                ctx(),
                ext.prepare(ctx()),
                new OnboardingInput(
                        "mcp",
                        "{\"name\":\"svc,prod\",\"endpoint\":\"https://mcp.example.com\","
                                + "\"category\":\"NETWORK\"}"));

        assertFalse(result.valid());
        assertTrue(result.safeFieldErrors().contains("name cannot contain a comma"));
    }

    @Test
    void validateRejectsUnknownCategory() {
        OnboardingExtension ext = extension();
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        OnboardingValidationResult r = ext.validate(
                ctx(),
                ext.prepare(ctx()),
                new OnboardingInput(
                        "mcp", "{\"name\":\"svc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"NOPE\"}"));
        assertFalse(r.valid());
    }

    @Test
    void commitPersistsAndCompensateDeletes() {
        OnboardingExtension ext = extension();
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        OnboardingInput input = new OnboardingInput(
                "mcp", "{\"name\":\"mysvc\",\"endpoint\":\"https://mcp.example.com\",\"category\":\"COMPUTE\"}");
        var commit = ext.plan(
                ctx(), ext.prepare(ctx()), new ValidatedOnboardingInput(input, OnboardingValidationResult.ok()));

        String receipt = commit.commit("op-1");
        assertEquals("mysvc", receipt);
        verify(lifecycleService)
                .persistSuccessfulHttp(
                        eq("mysvc"), eq("https://mcp.example.com"), eq(ToolCategory.COMPUTE), eq("owner-1"), any());

        // Idempotent re-commit must not double-store.
        commit.commit("op-1");
        verify(lifecycleService, times(2)).persistSuccessfulHttp(any(), any(), any(), any(), any());

        commit.compensate("op-1", receipt);
        verify(lifecycleService).deleteByNameAndUserId("mysvc", "owner-1");
    }

    @Test
    void emptyOptionalPayloadIsValidAndDoesNotPersist() {
        McpOnboardingExtension ext = extension();
        OnboardingValidationResult result = ext.validate(ctx(), ext.prepare(ctx()), new OnboardingInput("mcp", "{}"));
        assertTrue(result.valid());

        var commit = ext.plan(
                ctx(),
                ext.prepare(ctx()),
                new ValidatedOnboardingInput(new OnboardingInput("mcp", "{}"), OnboardingValidationResult.ok()));
        assertEquals("", commit.commit("op-empty"));
        verify(lifecycleService, never()).persistSuccessfulHttp(any(), any(), any(), any(), any());
    }

    @Test
    void uiServerListPayloadPersistsEveryHttpServer() {
        McpOnboardingExtension ext = extension();
        doNothing().when(lifecycleService).validateHttpRegistration(any(), anyMap());
        String payload = "{\"servers\":["
                + "{\"name\":\"one\",\"endpoint\":\"https://one.example\",\"category\":\"NETWORK\"},"
                + "{\"name\":\"two\",\"endpoint\":\"https://two.example\",\"category\":\"COMPUTE\"}]}";
        OnboardingInput input = new OnboardingInput("mcp", payload);
        assertTrue(ext.validate(ctx(), ext.prepare(ctx()), input).valid());

        var commit = ext.plan(
                ctx(), ext.prepare(ctx()), new ValidatedOnboardingInput(input, OnboardingValidationResult.ok()));
        assertEquals("one,two", commit.commit("op-many"));
        verify(lifecycleService)
                .persistSuccessfulHttp(
                        eq("one"), eq("https://one.example"), eq(ToolCategory.NETWORK), eq("owner-1"), anyMap());
        verify(lifecycleService)
                .persistSuccessfulHttp(
                        eq("two"), eq("https://two.example"), eq(ToolCategory.COMPUTE), eq("owner-1"), anyMap());
    }
}
