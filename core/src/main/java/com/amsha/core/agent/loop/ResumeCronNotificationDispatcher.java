package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.domain.model.AgentCronRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.persistence.api.AgentCronDto;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;

import org.jboss.logging.Logger;

/**
 * Dispatches completed or awaiting-approval cron results to the governed
 * cron notification pipeline upon checkpoint resumption.
 */
@ApplicationScoped
public class ResumeCronNotificationDispatcher {

    private static final Logger log = Logger.getLogger(ResumeCronNotificationDispatcher.class);

    private final CronStorePort cronStore;
    private final ChannelPluginRegistry pluginRegistry;
    private final DomainEventBus eventBus;

    public ResumeCronNotificationDispatcher() {
        this(null, null, null);
    }

    @Inject
    public ResumeCronNotificationDispatcher(
            CronStorePort cronStore, ChannelPluginRegistry pluginRegistry, DomainEventBus eventBus) {
        this.cronStore = cronStore;
        this.pluginRegistry = pluginRegistry;
        this.eventBus = eventBus;
    }

    public static boolean isCronSession(String sessionId) {
        return sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX);
    }

    public boolean dispatch(String sessionId, AgentResult result) {
        if (!isCronSession(sessionId) || cronStore == null) {
            return false;
        }

        String sub = sessionId.substring(AgentSentinels.CRON_SESSION_PREFIX.length());
        if (sub.length() < 36) {
            log.warnf("Malformed cron session id %s - cannot dispatch notification", sessionId);
            return false;
        }

        String jobId = sub.substring(0, 36);
        Optional<AgentCronDto> dtoOpt = cronStore.findById(jobId);
        if (dtoOpt.isEmpty()) {
            log.warnf("Cron job not found for id %s in session %s", jobId, sessionId);
            return false;
        }

        AgentCron job = AgentCronRecord.fromDto(dtoOpt.get());
        Optional<ChannelType> resolvedType = ChannelType.fromString(job.getChannelType());
        if (resolvedType.isEmpty()) {
            log.warnf("Cron %s has null/unknown channelType='%s'", jobId, job.getChannelType());
            return false;
        }

        ChannelType chType = resolvedType.get();
        String platformId = job.getChannelPlatformId();
        if (platformId == null || platformId.isBlank()) {
            platformId = job.getChatId();
        }
        if (platformId == null || platformId.isBlank()) {
            log.warnf("Cron %s has no channel platform or chat ID", jobId);
            return false;
        }

        String executionId = job.getLastDeliveryId();
        if (executionId == null || executionId.isBlank()) {
            executionId = CronSessionStatusSync.extractExecutionId(sub);
        }

        String toolName = result instanceof AgentResult.AwaitingApproval aa ? aa.toolName() : null;
        AgentResult.FailureCategory category = result instanceof AgentResult.Failed f ? f.category() : null;

        String renderedAnswer;
        if (result instanceof AgentResult.AwaitingApproval aa) {
            String displayGoal = job.getGoal() != null ? job.getGoal().replaceFirst("^CRON\\s+", "") : "";
            renderedAnswer = AgentResultFormatter.formatCronHitl(
                    job.getId(), displayGoal, aa.toolName(), aa.displayParamsJson());
        } else {
            String ans = AgentResultFormatter.format(result);
            renderedAnswer = (ans == null || ans.isBlank())
                    ? "⚠️ Task executed, but the agent returned an empty response for goal: " + job.getGoal()
                    : ans;
        }

        AgentJobCompleted event = new AgentJobCompleted(
                job.getId(),
                job.getUserId(),
                chType,
                platformId,
                executionId,
                renderedAnswer,
                category,
                sessionId,
                toolName,
                result instanceof AgentResult.AwaitingApproval aa ? aa.displayParamsJson() : null);

        boolean published = false;
        if (eventBus != null) {
            try {
                eventBus.publish(EventTypeCatalog.AGENT_JOB_COMPLETED, event);
                published = true;
                log.infof("CRON-RESUME-NOTIFY published event for session=%s tool=%s", sessionId, toolName);
            } catch (Exception e) {
                log.warnf(
                        "Failed to publish AGENT_JOB_COMPLETED for session %s: %s", sessionId, LogUtil.rootMessage(e));
            }
        }

        if (!published && pluginRegistry != null) {
            final String targetPlatformId = platformId;
            final String messageText = renderedAnswer;
            pluginRegistry.find(chType).map(ChannelPlugin::jobNotifier).ifPresent(n -> {
                if (toolName != null) {
                    n.notifyApprovalRequired(
                            targetPlatformId,
                            sessionId,
                            toolName,
                            messageText,
                            result instanceof AgentResult.AwaitingApproval aa ? aa.displayParamsJson() : null);
                } else {
                    n.notifyJobCompleted(targetPlatformId, messageText, sessionId);
                }
                log.infof("CRON-RESUME-NOTIFY direct dispatched for session=%s tool=%s", sessionId, toolName);
            });
            published = true;
        }

        return published;
    }
}
