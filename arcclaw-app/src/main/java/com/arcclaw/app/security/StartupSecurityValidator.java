package com.arcclaw.app.security;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class StartupSecurityValidator {

    @ConfigProperty(name = "arcclaw.vault.pepper")
    String pepper;

    @ConfigProperty(name = "arcclaw.audit.hmac-key")
    String hmacKey;

    @ConfigProperty(name = "quarkus.profile")
    String profile;

    void onStart(@Observes StartupEvent ev) {
        if ("prod".equals(profile) || "staging".equals(profile)) {
            if (pepper.startsWith("dev-pepper")) {
                throw new IllegalStateException(
                    "[SECURITY] arcclaw.vault.pepper is using the dev default. " +
                    "Set ARCCLAW_VAULT_PEPPER in your .env before running in prod.");
            }
            if (hmacKey.startsWith("dev-audit-hmac-key")) {
                throw new IllegalStateException(
                    "[SECURITY] arcclaw.audit.hmac-key is using the dev default. " +
                    "Set ARCCLAW_AUDIT_HMAC_KEY in your .env before running in prod.");
            }
        }
    }
}

