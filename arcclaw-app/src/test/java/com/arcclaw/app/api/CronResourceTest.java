package com.arcclaw.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.persistence.api.AgentCronDto;
import com.arcclaw.persistence.api.CronRunStorePort;
import com.arcclaw.persistence.api.CronStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CronResourceTest {

    @Mock
    CronStorePort cronStore;

    @Mock
    CronRunStorePort cronRunStore;

    @Mock
    AuditService auditService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    CronResource resource;

    private static final String USER_ID = "test-user";
    private static final String CRON_ID = "cron-123";

    @BeforeEach
    void setUp() {
        resource = new CronResource();
        // Inject mocks via reflection (no CDI in unit tests)
        setField(resource, "cronStore", cronStore);
        setField(resource, "cronRunStore", cronRunStore);
        setField(resource, "auditService", auditService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void listCronsReturns200WithPaginatedResults() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        AgentCronDto dto = sampleCron();
        when(cronStore.findByUserId(USER_ID, null, 0, 50)).thenReturn(List.of(dto));
        when(cronStore.countByUserId(USER_ID, null)).thenReturn(1L);

        Response resp = resource.listCrons(null, 0, 50);

        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals(1L, body.get("total"));
    }

    @Test
    void getCronReturns200WhenFound() {
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(sampleCron()));

        Response resp = resource.getCron(CRON_ID);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getCronReturns404WhenNotFound() {
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.empty());

        Response resp = resource.getCron(CRON_ID);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void deleteCronReturns404WhenNotFound() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(cronStore.deleteByIdAndUserId(CRON_ID, USER_ID)).thenReturn(0);

        Response resp = resource.deleteCron(CRON_ID);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void deleteCronReturns200WhenDeleted() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(cronStore.deleteByIdAndUserId(CRON_ID, USER_ID)).thenReturn(1);

        Response resp = resource.deleteCron(CRON_ID);

        assertEquals(200, resp.getStatus());
        verify(auditService).recordMandatory(anyString(), eq(USER_ID), anyString(), any(), anyString());
    }

    @Test
    void pauseCronReturns403WhenNotOwner() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        AgentCronDto otherUserCron = new AgentCronDto(
                CRON_ID,
                "sess-1",
                "other-user",
                "chat-1",
                "telegram",
                "tg-1",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                Instant.now(),
                null,
                "ACTIVE",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(otherUserCron));

        Response resp = resource.pauseCron(CRON_ID);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void pauseCronReturns200WhenActive() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        AgentCronDto activeCron = new AgentCronDto(
                CRON_ID,
                "sess-1",
                USER_ID,
                "chat-1",
                "telegram",
                "tg-1",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                Instant.now(),
                null,
                "ACTIVE",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(activeCron));

        Response resp = resource.pauseCron(CRON_ID);

        assertEquals(200, resp.getStatus());
        verify(cronStore).markCompleted(eq(CRON_ID), any());
    }

    @Test
    void pauseCronReturns409WhenAlreadyCompleted() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        AgentCronDto completedCron = new AgentCronDto(
                CRON_ID,
                "sess-1",
                USER_ID,
                "chat-1",
                "telegram",
                "tg-1",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                Instant.now(),
                Instant.now(),
                "COMPLETED",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(completedCron));

        Response resp = resource.pauseCron(CRON_ID);

        assertEquals(409, resp.getStatus());
    }

    @Test
    void resumeCronReturns200WhenCompleted() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        AgentCronDto completedCron = new AgentCronDto(
                CRON_ID,
                "sess-1",
                USER_ID,
                "chat-1",
                "telegram",
                "tg-1",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                Instant.now(),
                Instant.now(),
                "COMPLETED",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(completedCron));

        Response resp = resource.resumeCron(CRON_ID);

        assertEquals(200, resp.getStatus());
        verify(cronStore).save(any(AgentCronDto.class));
    }

    @Test
    void resumeCronReturns409WhenActive() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        AgentCronDto activeCron = new AgentCronDto(
                CRON_ID,
                "sess-1",
                USER_ID,
                "chat-1",
                "telegram",
                "tg-1",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                Instant.now(),
                null,
                "ACTIVE",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(activeCron));

        Response resp = resource.resumeCron(CRON_ID);

        assertEquals(409, resp.getStatus());
    }

    @Test
    void triggerCronReturns200WhenClaimed() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(sampleCron()));
        when(cronStore.claimForFire(CRON_ID)).thenReturn(true);

        Response resp = resource.triggerCron(CRON_ID);

        assertEquals(200, resp.getStatus());
        verify(cronStore).releaseClaimToActive(CRON_ID);
    }

    @Test
    void triggerCronReturns409WhenCannotClaim() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(sampleCron()));
        when(cronStore.claimForFire(CRON_ID)).thenReturn(false);

        Response resp = resource.triggerCron(CRON_ID);

        assertEquals(409, resp.getStatus());
    }

    @Test
    void getCronRunsReturns200WithResults() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.of(sampleCron()));
        when(cronRunStore.findByCronId(CRON_ID, 0, 20)).thenReturn(List.of());
        when(cronRunStore.countByCronId(CRON_ID)).thenReturn(0L);

        Response resp = resource.getCronRuns(CRON_ID, 0, 20);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getCronRunsReturns404WhenCronNotFound() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(cronStore.findById(CRON_ID)).thenReturn(Optional.empty());

        Response resp = resource.getCronRuns(CRON_ID, 0, 20);

        assertEquals(404, resp.getStatus());
    }

    private AgentCronDto sampleCron() {
        return new AgentCronDto(
                CRON_ID,
                "sess-1",
                USER_ID,
                "chat-1",
                "telegram",
                "tg-1",
                "0 * * * *",
                "UTC",
                false,
                "do something",
                Instant.now(),
                null,
                "ACTIVE",
                "coordinator",
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
