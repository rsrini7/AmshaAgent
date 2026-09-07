package com.amsha.app.ui.dto;

public record DbConnectionPoolSummary(
        String backend,
        String status,
        boolean poolEnabled,
        Integer activeConnections,
        Integer availableConnections,
        Integer maxPoolSize,
        Integer awaitingConnections) {}
