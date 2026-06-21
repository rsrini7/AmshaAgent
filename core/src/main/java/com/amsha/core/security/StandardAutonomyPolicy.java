package com.amsha.core.security;

import jakarta.enterprise.context.ApplicationScoped;

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
