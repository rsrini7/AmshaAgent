package com.amsha.onboarding.testutil;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.security.Principal;

import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Test-only authentication filter for the onboarding module's standalone test app. The production
 * deployment runs onboarding inside the app module, where {@code ApiKeyAuthFilter} authenticates;
 * this provider supplies the same SecurityContext principal contract for onboarding's own tests.
 */
@Provider
@Priority(1000)
public class TestApiKeyAuthFilter implements ContainerRequestFilter {

    static final String DEFAULT_INTERNAL_KEY = "key-internal-changeme";

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalKey;

    @Inject
    Instance<BootstrapStateStorePort> bootstrapStateStore;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String apiKey = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = requestContext.getHeaderString("X-Amsha-API-Key");
        }
        Logger.getLogger(TestApiKeyAuthFilter.class)
                .infof("TESTAUTH path=%s hasKey=%s", requestContext.getUriInfo().getPath(), apiKey != null);
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        String expectedInternal = internalKey != null ? internalKey : DEFAULT_INTERNAL_KEY;
        String owner;
        if (apiKey.equals(expectedInternal) || apiKey.equals(DEFAULT_INTERNAL_KEY)) {
            owner = "internal";
        } else if ("sk-testrawkey123".equals(apiKey) && bootstrapStateStore.isResolvable()) {
            owner = bootstrapStateStore
                    .get()
                    .getState()
                    .map(BootstrapStateDto::ownerId)
                    .orElse(apiKey);
        } else {
            owner = apiKey;
        }
        final String fOwner = owner;
        Logger.getLogger(TestApiKeyAuthFilter.class)
                .infof("TESTAUTH SET owner=%s apiKey=%s expected=%s", owner, apiKey, expectedInternal);
        requestContext.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> fOwner;
            }

            @Override
            public boolean isUserInRole(String role) {
                return "internal".equals(fOwner)
                        && ("OWNER".equalsIgnoreCase(role) || "internal".equalsIgnoreCase(role));
            }

            @Override
            public boolean isSecure() {
                return requestContext.getSecurityContext().isSecure();
            }

            @Override
            public String getAuthenticationScheme() {
                return "APIKEY";
            }
        });
    }
}
