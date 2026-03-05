package com.arcclaw.core.pipeline.mock;

import com.arcclaw.channel.api.ChannelAllowlist;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

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