package com.amsha.app.security;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GithubIdentityProvider implements ExternalIdentityProvider {

    private static final Logger log = Logger.getLogger(GithubIdentityProvider.class);

    @ConfigProperty(name = "amsha.auth.idp.github.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "amsha.auth.idp.github.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "amsha.auth.idp.github.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "amsha.auth.idp.github.redirect-uri")
    Optional<String> redirectUriConfig;

    private final Client httpClient = ClientBuilder.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    @Override
    public String getProviderId() {
        return "github";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @PreDestroy
    void close() {
        httpClient.close();
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUri) {
        if (!enabled) {
            throw new IllegalStateException("GitHub OAuth provider is disabled");
        }
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + clientId.orElse("")
                + "&redirect_uri=" + encode(resolveRedirectUri(redirectUri))
                + "&scope=read:user+user:email"
                + "&state=" + encode(state);
    }

    @Override
    public ExternalUserProfile authenticate(String code, String redirectUri) {
        if (!enabled) {
            throw new IllegalStateException("GitHub OAuth provider is disabled");
        }

        try {
            // Exchange code for token
            Form form = new Form()
                    .param("client_id", clientId.orElse(""))
                    .param("client_secret", clientSecret.orElse(""))
                    .param("code", code)
                    .param("redirect_uri", resolveRedirectUri(redirectUri));

            Map<?, ?> tokenResponse = httpClient
                    .target("https://github.com/login/oauth/access_token")
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .post(Entity.form(form), Map.class);

            String accessToken = (String) tokenResponse.get("access_token");
            if (accessToken == null) {
                throw new IllegalStateException("Access token missing from GitHub token response");
            }

            // Fetch user profile
            Map<?, ?> userProfile = httpClient
                    .target("https://api.github.com/user")
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .header("Authorization", "token " + accessToken)
                    .get(Map.class);

            Object idObj = userProfile.get("id");
            String providerUserId = idObj != null ? idObj.toString() : null;
            String name = (String) userProfile.get("name");
            String avatarUrl = (String) userProfile.get("avatar_url");
            String email = (String) userProfile.get("email");
            boolean emailVerified = false;

            if (providerUserId == null) {
                throw new IllegalStateException("GitHub user ID missing from profile response");
            }

            // GitHub's profile response does not expose verification status. Resolve it from the
            // email endpoint even when the profile email is public.
            try {
                List<?> emails = httpClient
                        .target("https://api.github.com/user/emails")
                        .request(MediaType.APPLICATION_JSON_TYPE)
                        .header("Authorization", "token " + accessToken)
                        .get(List.class);

                for (Object item : emails) {
                    if (item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("verified"))) {
                        String candidate = (String) map.get("email");
                        if (candidate != null && candidate.equalsIgnoreCase(email)) {
                            emailVerified = true;
                        }
                        if (candidate != null && Boolean.TRUE.equals(map.get("primary"))) {
                            email = candidate;
                            emailVerified = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to verify email with GitHub: " + e.getMessage());
            }

            return new ExternalUserProfile(providerUserId, email, emailVerified, name, avatarUrl);

        } catch (Exception e) {
            log.error("GitHub OAuth authentication failed: " + e.getMessage(), e);
            throw new IllegalStateException("Failed to authenticate with GitHub provider", e);
        }
    }

    private String resolveRedirectUri(String requestRedirectUri) {
        return redirectUriConfig.isPresent() && !redirectUriConfig.get().isBlank()
                ? redirectUriConfig.get()
                : requestRedirectUri;
    }
}
