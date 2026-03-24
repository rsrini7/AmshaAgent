package com.arcclaw.core.pipeline;

import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.security.AutonomyLevel;

public record SessionContext(
    String sessionId,
    ChannelUser user,
    AutonomyLevel autonomyLevel,
    boolean isResumeSignal
) {}
