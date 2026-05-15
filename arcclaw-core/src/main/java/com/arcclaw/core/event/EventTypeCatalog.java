package com.arcclaw.core.event;

import com.arcclaw.channel.api.hitl.ApprovalDecisionEvent;
import com.arcclaw.core.agent.event.AgentStateChangedEvent;
import com.arcclaw.core.compaction.CompactionRequest;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.cron.CronTaskScheduled;
import com.arcclaw.core.lifecycle.ApprovalTimedOutEvent;
import com.arcclaw.core.persistence.PersistenceEvent;
import com.arcclaw.core.tools.CheckListEvent;

/**
 * Central catalog of all domain event types used with {@link DomainEventBus}.
 *
 * <p>Every event publication across the codebase MUST reference a constant from
 * this catalog to ensure discoverability and compile-time type safety.
 *
 * <p>To add a new event type:
 * <ol>
 *   <li>Create the event payload class (typically a {@code record})</li>
 *   <li>Add a {@code public static final Class<YourEvent>} constant in this catalog</li>
 *   <li>Inject {@link DomainEventBus} and publish via
 *       {@code eventBus.publish(EventTypeCatalog.YOUR_EVENT, new YourEvent(...))}</li>
 *   <li>Observe with {@code @Observes YourEvent event} in the consumer</li>
 * </ol>
 */
public final class EventTypeCatalog {

    /** Fired when a cron job completes (success, failure, or awaiting approval). */
    public static final Class<AgentJobCompleted> AGENT_JOB_COMPLETED = AgentJobCompleted.class;

    /** Fired when the agent loop starts or ends (status: active, idle, waiting-approval, etc.). */
    public static final Class<AgentStateChangedEvent> AGENT_STATE_CHANGED = AgentStateChangedEvent.class;

    /** Fired when a human approves or denies a tool. Observed by channel notification services. */
    public static final Class<ApprovalDecisionEvent> APPROVAL_DECISION = ApprovalDecisionEvent.class;

    /** Fired when an awaiting-approval session times out. Observed by channel timeout dispatchers. */
    public static final Class<ApprovalTimedOutEvent> APPROVAL_TIMED_OUT = ApprovalTimedOutEvent.class;

    /** Fired when a CheckListTool creates or updates a checklist. */
    public static final Class<CheckListEvent> CHECK_LIST = CheckListEvent.class;

    /** Fired when context compaction should be triggered for a session. */
    public static final Class<CompactionRequest> COMPACTION_REQUEST = CompactionRequest.class;

    /** Fired when a new cron task is scheduled. Observed by {@code AgentCronScheduler}. */
    public static final Class<CronTaskScheduled> CRON_TASK_SCHEDULED = CronTaskScheduled.class;

    /** Fired to flush queued approval notifications for a chat. */
    public static final Class<FlushQueuedNotifications> FLUSH_QUEUED_NOTIFICATIONS = FlushQueuedNotifications.class;

    /** Fired for critical and telemetry persistence events. */
    public static final Class<PersistenceEvent> PERSISTENCE = PersistenceEvent.class;

    private EventTypeCatalog() {
    }
}
