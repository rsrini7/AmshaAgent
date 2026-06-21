package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.ChannelAllowlist;

/**
 * CLI implementation of {@link ChannelAllowlist}.
 * CLI access is always allowed (the process is already authenticated by the local user).
 */
@ApplicationScoped
public class CliAllowlist implements ChannelAllowlist {

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
        return "Access denied.";
    }
}
