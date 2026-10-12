package com.amsha.common.http;

/**
 * Canonical HTTP header names, media types, and Server-Sent Events (SSE) framing
 * tokens shared by the HTTP clients across the reactor.
 * <p>
 * These are wire-protocol tokens: a typo does not fail compilation, it silently
 * breaks request negotiation or SSE streaming (for example a misspelled
 * {@code data:} prefix makes every streamed line be dropped). Centralising them
 * gives a single source of truth so a correction lands everywhere at once.
 * <p>
 * Domain-specific surfaces stay in their own homes, for example Amsha's
 * {@code X-Amsha-API-Key} header and the loopback predicate live in
 * {@code com.amsha.core.util.HttpConstants}.
 */
public final class HttpProtocol {

    private HttpProtocol() {}

    // -- Header names ------------------------------------------------------

    /** {@code Content-Type} request/response header name. */
    public static final String HEADER_CONTENT_TYPE = "Content-Type";

    /** {@code Accept} request header name. */
    public static final String HEADER_ACCEPT = "Accept";

    /** {@code Authorization} request header name. */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    // -- Media types -------------------------------------------------------

    /** {@code application/json} media type. */
    public static final String MEDIA_TYPE_JSON = "application/json";

    /** {@code text/event-stream} media type used for SSE responses. */
    public static final String MEDIA_TYPE_TEXT_EVENT_STREAM = "text/event-stream";

    /** {@code application/x-www-form-urlencoded} media type used for OAuth token exchange. */
    public static final String MEDIA_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";

    // -- Authentication schemes -------------------------------------------

    /**
     * {@code Bearer} authorization-scheme prefix, including the trailing space so it can be
     * concatenated directly with a token.
     * <p>
     * Not every scheme is {@code Bearer}: GitHub's API uses the {@code token} scheme, so that
     * prefix is deliberately kept local to the GitHub client rather than centralised here.
     */
    public static final String AUTH_SCHEME_BEARER = "Bearer ";

    // -- Server-Sent Events framing ---------------------------------------

    /** {@code data:} SSE field prefix. */
    public static final String SSE_DATA_PREFIX = "data:";

    /** {@code [DONE]} sentinel that terminates an OpenAI-style SSE stream. */
    public static final String SSE_DONE = "[DONE]";
}
