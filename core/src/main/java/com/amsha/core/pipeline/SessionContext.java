package com.amsha.core.pipeline;

import com.amsha.channel.api.ChannelUser;
import com.amsha.security.api.AutonomyLevel;

public record SessionContext(String sessionId, ChannelUser user, AutonomyLevel autonomyLevel, boolean isResumeSignal) {}
