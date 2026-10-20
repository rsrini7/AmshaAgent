package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.amsha.channel.api.ChannelType;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApprovalNotificationServiceTest {

    private static final String CRON_SESSION_ID = "cron-job-1:delegate:brahma:1";
    private static final String DISPLAY_PARAMS = "{\"command\":\"python3 factorial.py\"}";

    @Mock
    DomainEventBus eventBus;

    private ApprovalNotificationService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalNotificationService();
        service.eventBus = eventBus;
    }

    @Test
    void resumedCronApprovalIncludesItsSessionAndDisplayParams() {
        AgentCronRecord cron = new AgentCronRecord();
        cron.setId("job-1");
        cron.setUserId("user-1");
        cron.setChatId("chat-1");
        cron.setChannelType(ChannelType.WEBSOCKET.id());
        cron.setGoal("write factorial program");
        AgentResult result = AgentResult.awaitingApproval("sandbox_exec", DISPLAY_PARAMS, CRON_SESSION_ID, List.of());

        service.createJobNotifier().accept(cron, result);

        ArgumentCaptor<AgentJobCompleted> event = ArgumentCaptor.forClass(AgentJobCompleted.class);
        verify(eventBus).publish(eq(EventTypeCatalog.AGENT_JOB_COMPLETED), event.capture());
        assertEquals(CRON_SESSION_ID, event.getValue().cronSessionId());
        assertEquals(DISPLAY_PARAMS, event.getValue().displayToolParamsJson());
    }
}
