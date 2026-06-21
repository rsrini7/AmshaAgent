package com.amsha.core.pipeline.mock;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.ChannelAllowlist;

import io.quarkus.test.Mock;

@Mock
@ApplicationScoped
public class MockChannelAllowlist implements ChannelAllowlist {
    @Override
    public boolean isAllowed(String platformUserId, String username) {
        return true;
    }

    @Override
    public boolean isSilentBlock() {
        return false;
    }

    @Override
    public String getBlockResponse() {
        return "Blocked by mock";
    }
}
