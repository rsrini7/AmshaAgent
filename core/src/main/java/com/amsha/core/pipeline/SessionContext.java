package com.amsha.core.pipeline;

import com.amsha.channel.api.ChannelUser;
import com.amsha.core.security.AutonomyLevel;

public record SessionContext(String sessionId, ChannelUser user, AutonomyLevel autonomyLevel, boolean isResumeSignal) {}
