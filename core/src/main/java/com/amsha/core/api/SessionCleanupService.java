package com.amsha.core.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.api.dto.RunAgentRequest;
import com.amsha.core.api.dto.ServiceResult;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.reflex.ReflexRegistry;
import com.amsha.core.reflex.ReflexResult;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.AutonomyLevel;

import org.jboss.logging.Logger;

public class SessionCleanupService {

    private final Logger log;
    private final SessionHeartbeatTracker heartbeatTracker;
    private final ReflexRegistry reflexRegistry;
    private final AuditService auditService;
    private final String userId;

    private static final List<String> NON_SLASH_REFLEX_PREFIXES = List.of("deletecron");

    private static final String REST_USER_ID = ApiConstants.REST_USER_ID;
    private static final String REST_USER_NAME = ApiConstants.REST_USER_NAME;
    private static final String REST_API_SESSION_PREFIX = "rest-api-";
    private static final String REST_CHAT_SESSION_PREFIX = "rest-chat-";

    public SessionCleanupService(
            Logger log,
            SessionHeartbeatTracker heartbeatTracker,
            ReflexRegistry reflexRegistry,
            AuditService auditService,
            String userId) {
        this.log = log;
        this.heartbeatTracker = heartbeatTracker;
        this.reflexRegistry = reflexRegistry;
        this.auditService = auditService;
        this.userId = userId;
    }

    public Optional<ServiceResult<Object>> validateRole(String role) {
        if (!AgentRoleEnum.isKnownRole(role)) {
            return Optional.of(ServiceResult.error("UNKNOWN_ROLE", "Unknown role: " + role));
        }
        return Optional.empty();
    }

    public Optional<ServiceResult<Object>> validateSession(String sessionId) {
        if (!heartbeatTracker.exists(sessionId)) {
            return Optional.of(ServiceResult.error("SESSION_NOT_FOUND", "Session not found: " + sessionId));
        }

        Optional<String> owner = heartbeatTracker.getOwner(sessionId);
        if (owner.isPresent()) {
            if (userId != null && !owner.get().equals(userId)) {
                log.warnf(
                        "[SESSION-ISOLATION] key=%s attempted access to session=%s owned by key=%s",
                        userId, sessionId, owner.get());

                auditService.recordMandatory(
                        sessionId,
                        userId,
                        AgentSentinels.SYSTEM_ROLE,
                        SecurityEventType.UNAUTHORIZED_ACCESS,
                        "Key " + userId + " attempted cross-key session access");

                return Optional.of(
                        ServiceResult.error("SESSION_ACCESS_DENIED", "API key not authorized for this session"));
            }
        }
        return Optional.empty();
    }

    public MessageContext createRestContext(String sessionId, String goal) {
        ChannelUser user = new ChannelUser(REST_USER_ID, REST_USER_ID, REST_USER_NAME, ChannelType.REST_API);
        ChannelMessage msg =
                new ChannelMessage.RestApiMessage(user, goal, REST_API_SESSION_PREFIX + sessionId, Instant.now());
        return new MessageContext(REST_CHAT_SESSION_PREFIX + sessionId, user, msg);
    }

    public AutonomyLevel getOrDefaultAutonomy(String sessionId) {
        AutonomyLevel level = heartbeatTracker.getAutonomyLevel(sessionId);
        return (level != null) ? level : AutonomyLevel.SUPERVISED;
    }

    public Optional<ServiceResult<Object>> handleSlashCommand(String sessionId, RunAgentRequest request) {
        String goal = request.goal();
        if (goal == null
                || (!goal.startsWith("/")
                        && NON_SLASH_REFLEX_PREFIXES.stream().noneMatch(goal.toLowerCase(Locale.ROOT)::startsWith)))
            return Optional.empty();

        try {
            Callable<ServiceResult<Object>> op = () -> {
                String canonicalRole =
                        AgentRoleEnum.fromString(request.agentRole()).toScopedName();
                String channel = ChannelType.REST_API.name();
                return WorkspaceContext.<ServiceResult<Object>>run(
                        sessionId, REST_USER_ID, sessionId, AutonomyLevel.FULL, canonicalRole, channel, () -> {
                            MessageContext context = createRestContext(sessionId, goal);
                            ReflexResult result =
                                    reflexRegistry.processIfReflex(sessionId, REST_USER_ID, sessionId, goal, context);
                            return result.commandResponse()
                                    .map(r -> ServiceResult.success((Object) Map.of("status", "OK", "message", r)))
                                    .orElse(null);
                        });
            };
            return Optional.ofNullable(WorkspaceScopeHolder.call(sessionId, op));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
