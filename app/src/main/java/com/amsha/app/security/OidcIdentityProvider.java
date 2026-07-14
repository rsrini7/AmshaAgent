package com.amsha.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OidcIdentityProvider implements ExternalIdentityProvider {

    private static final Logger log = Logger.getLogger(OidcIdentityProvider.class);

    @ConfigProperty(name = "amsha.auth.idp.oidc.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "amsha.auth.idp.oidc.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "amsha.auth.idp.oidc.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "amsha.auth.idp.oidc.authorization-url")
    Optional<String> authorizationUrl;

    @ConfigProperty(name = "amsha.auth.idp.oidc.token-url")
    Optional<String> tokenUrl;

    @ConfigProperty(name = "amsha.auth.idp.oidc.userinfo-url")
    Optional<String> userinfoUrl;

    private final Client httpClient = ClientBuilder.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @Override
    public String getProviderId() {
        return "oidc";
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUri) {
        if (!enabled) {
            throw new IllegalStateException("OIDC identity provider is disabled");
        }
        return authorizationUrl.orElse("") + "?client_id=" + clientId.orElse("")
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=openid+email+profile"
                + "&state=" + encode(state);
    }

    @Override
    public ExternalUserProfile authenticate(String code, String redirectUri) {
        if (!enabled) {
            throw new IllegalStateException("OIDC identity provider is disabled");
        }

        try {
            // Exchange code for token
            Form form = new Form()
                    .param("grant_type", "authorization_code")
                    .param("code", code)
                    .param("redirect_uri", redirectUri)
                    .param("client_id", clientId.orElse(""))
                    .param("client_secret", clientSecret.orElse(""));

            Map<?, ?> tokenResponse = httpClient
                    .target(tokenUrl.orElse(""))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.form(form), Map.class);

            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken == null) {
                throw new IllegalStateException("Access token missing from OIDC token response");
            }

            // Fetch user info
            Map<?, ?> userInfo = httpClient
                    .target(userinfoUrl.orElse(""))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header("Authorization", "Bearer " + accessToken)
                    .get(Map.class);

            String sub = (String) userInfo.get("sub");
            String email = (String) userInfo.get("email");
            String name = (String) userInfo.get("name");
            String picture = (String) userInfo.get("picture");

            if (sub == null) {
                throw new IllegalStateException("Subject (sub) missing from OIDC userinfo response");
            }

            return new ExternalUserProfile(sub, email, name, picture);

        } catch (Exception e) {
            log.error("OIDC authentication failed: " + e.getMessage(), e);
            throw new IllegalStateException("Failed to authenticate with OIDC provider", e);
        }
    }
}
