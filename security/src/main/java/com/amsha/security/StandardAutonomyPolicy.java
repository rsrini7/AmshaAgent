package com.amsha.security;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.core.security.AutonomyPolicy;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import io.quarkus.arc.Unremovable;

@Unremovable
@ApplicationScoped
public class StandardAutonomyPolicy implements AutonomyPolicy {
    @Override
    public boolean isAllowed(AutonomyLevel level, ToolOperation operation) {
        return switch (level) {
            case FULL -> true;
            case SUPERVISED -> operation == ToolOperation.READ_FS || operation == ToolOperation.SCHEDULE_AUTONOMOUS;
            case BACKGROUND ->
                operation == ToolOperation.READ_FS
                        || operation == ToolOperation.NETWORK_OUTBOUND
                        || operation == ToolOperation.DELEGATE_AUTONOMOUS;
            case READONLY -> operation == ToolOperation.READ_FS;
        };
    }
}
