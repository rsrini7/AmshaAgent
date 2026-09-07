package com.amsha.app.ui.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DbQueryRequest(
        @JsonProperty("sql") String sql,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("workspaceId") String workspaceId,
        @JsonProperty("sessionId") String sessionId) {

    public DbQueryRequest(String sql, Integer limit) {
        this(sql, limit, null, null);
    }
}
