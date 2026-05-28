package com.arcclaw.channel.web;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.channel.api.ChannelServices;

@Path("/chat/ws-token")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WsAuthService {

    private static final String COOKIE_API_KEY = "ARCCLAW_API_KEY";

    @Inject
    ChannelServices channelServices;

    @Inject
    WsTokenStore tokenStore;

    @Context
    HttpHeaders httpHeaders;

    @POST
    public WsTokenResponse issueToken(@HeaderParam("X-ArcClaw-API-Key") String apiKey) {
        String resolvedKey = apiKey;
        if (resolvedKey == null || resolvedKey.isBlank()) {
            Cookie apiCookie = httpHeaders.getCookies().get(COOKIE_API_KEY);
            if (apiCookie != null) {
                resolvedKey = apiCookie.getValue();
            }
        }
        if (resolvedKey == null || resolvedKey.isBlank() || !channelServices.isValidIdentity(resolvedKey)) {
            throw new WebApplicationException("Invalid or missing API key", Response.Status.UNAUTHORIZED);
        }
        String token = tokenStore.generateToken();
        return new WsTokenResponse(token);
    }

    public static class WsTokenResponse {
        public String token;

        public WsTokenResponse(String token) {
            this.token = token;
        }

        public WsTokenResponse() {}
    }
}
