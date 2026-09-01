package com.amsha.security.api;

/**
 * Purpose of an outbound HTTP request. Used for HITL gating, audit, and per-purpose timeout policy.
 */
public enum OutboundPurpose {
    /** Provider catalog probe (e.g., discovering available models). */
    CATALOG_PROBE,
    /** Provider API call (e.g., chat completion, embedding). */
    PROVIDER_API_CALL,
    /** Provider model discovery (listing available models). */
    PROVIDER_MODEL_DISCOVERY,
    /** OAuth callback/redirect. */
    OAUTH_CALLBACK,
    /** MCP server connection (initial handshake). */
    MCP_CONNECT,
    /** Explicit activation of a discovered MCP tool. */
    MCP_TOOL_ENABLE,
    /** MCP tool invocation. */
    MCP_TOOL_CALL,
    /** Governed media attachment download. */
    MEDIA_DOWNLOAD
}
