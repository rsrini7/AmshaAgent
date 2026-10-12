package com.amsha.channel.web;

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
import jakarta.ws.rs.core.SecurityContext;

import java.util.Optional;

import com.amsha.channel.api.ChannelServices;
import com.amsha.core.util.HttpConstants;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/chat/ws-token")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class WsAuthResource {

    private static final String COOKIE_API_KEY = HttpConstants.COOKIE_AMSHA_API_KEY;

    @Inject
    ChannelServices channelServices;

    @Inject
    WsTokenStore tokenStore;

    @Context
    HttpHeaders httpHeaders;

    @Context
    SecurityContext securityContext;

    @POST
    public WsTokenResponse issueToken(@HeaderParam(HttpConstants.X_AMSHA_API_KEY) String apiKey) {
        String resolvedKey = apiKey;
        if (resolvedKey == null || resolvedKey.isBlank()) {
            Cookie apiCookie = httpHeaders.getCookies().get(COOKIE_API_KEY);
            if (apiCookie != null) {
                resolvedKey = apiCookie.getValue();
            }
        }
        Optional<String> owner = resolveOwner(resolvedKey);
        if (owner.isEmpty()) {
            throw new WebApplicationException("Invalid or missing API key", Response.Status.UNAUTHORIZED);
        }
        String token = tokenStore.generateToken(owner.get());
        return new WsTokenResponse(token);
    }

    private Optional<String> resolveOwner(String credential) {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            String principal = securityContext.getUserPrincipal().getName();
            if (principal != null && !principal.isBlank() && !"anonymous".equalsIgnoreCase(principal)) {
                return Optional.of(principal);
            }
        }
        if (credential == null || credential.isBlank() || !channelServices.isValidIdentity(credential)) {
            return Optional.empty();
        }
        return channelServices.resolveIdentity(credential);
    }

    public static class WsTokenResponse {
        public String token;

        public WsTokenResponse(String token) {
            this.token = token;
        }

        public WsTokenResponse() {}
    }
}
