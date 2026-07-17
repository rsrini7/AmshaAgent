package com.amsha.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Idempotent startup migration that carries the legacy system-vault LLM material
 * ({@code llm.provider.key}/{@code llm.model}/{@code llm.api.key}) into the typed
 * {@code SYSTEM}-scoped connection/profile/route records required by the O3 routing
 * resolver. It does not copy or reassign any secret: the migrated connection simply
 * references the existing system-vault key by name, so effective routing is unchanged.
 *
 * <p>Behavior:
 * <ul>
 *   <li>If a {@code SYSTEM} route already exists, the migration is a no-op (idempotent, safe to
 *       re-run on every startup or after rollback).</li>
 *   <li>If the legacy API key is absent, the migration is skipped and a bounded health warning is
 *       emitted; the legacy {@code ChatModelProducer} read path remains active and startup continues.</li>
 *   <li>Any failure records a health warning and leaves the legacy path active — startup never blocks
 *       because an administrator missed onboarding.</li>
 * </ul>
 */
@ApplicationScoped
public class SystemProfileMigration {

    private static final Logger log = Logger.getLogger(SystemProfileMigration.class);

    private static final String SYSTEM_SCOPE_ID = "SYSTEM";
    private static final String SYSTEM_CONN_ID = "sys-legacy";
    private static final String SYSTEM_PROFILE_ID = "sys-legacy-profile";
    private static final String SYSTEM_ROUTE_ID = "sys-legacy-route";
    static final String SYSTEM_SLOT_ID = "api_key";

    private final SecretVault vault;
    private final LlmProfileStorePort profileStore;

    @Inject
    public SystemProfileMigration(SecretVault vault, LlmProfileStorePort profileStore) {
        this.vault = vault;
        this.profileStore = profileStore;
    }

    protected SystemProfileMigration() {
        this(null, null);
    }

    void onStart(@Observes @Priority(50) StartupEvent ev) {
        try {
            migrateIfNeeded();
        } catch (Exception e) {
            log.warnf(
                    "[SYSTEM-MIGRATION] Legacy SYSTEM-profile migration skipped due to error; "
                            + "legacy routing remains active: %s",
                    LogUtil.rootMessage(e));
        }
    }

    void migrateIfNeeded() {
        if (!profileStore.listRoutesByScope(SYSTEM_SCOPE_ID, SYSTEM_SCOPE_ID).isEmpty()) {
            log.debugf("[SYSTEM-MIGRATION] SYSTEM route already present; skipping migration");
            return;
        }
        Optional<String> provider = readSystemString(VaultConstants.LLM_PROVIDER_KEY);
        Optional<String> model = readSystemString(VaultConstants.LLM_MODEL_KEY);
        if (provider.isEmpty() || provider.get().isBlank()) {
            log.infof("[SYSTEM-MIGRATION] No legacy provider configured; skipping SYSTEM-profile migration");
            return;
        }
        if (!vault.systemKeyExists(VaultConstants.LLM_API_KEY)) {
            log.warnf(
                    "[SYSTEM-MIGRATION] Legacy %s missing; skipping SYSTEM-profile migration. "
                            + "Legacy routing remains active. Set the key or complete onboarding.",
                    VaultConstants.LLM_API_KEY);
            return;
        }
        String modelId = model.filter(m -> !m.isBlank()).orElse("gpt-4o");
        Instant now = Instant.now();
        LlmConnectionDto connection = new LlmConnectionDto(
                SYSTEM_CONN_ID,
                "SYSTEM",
                SYSTEM_SCOPE_ID,
                "system",
                "Legacy system LLM connection",
                provider.get().trim(),
                "default",
                null,
                "API_KEY",
                null,
                "ACTIVE",
                1,
                now,
                now);
        profileStore.saveConnection(connection);
        profileStore.saveModelProfile(new ModelProfileDto(
                SYSTEM_PROFILE_ID, SYSTEM_CONN_ID, modelId, "NATIVE", null, null, true, 1, now, now));
        profileStore.saveRouteAssignment(new ModelRouteAssignmentDto(
                SYSTEM_ROUTE_ID,
                "SYSTEM",
                SYSTEM_SCOPE_ID,
                "DEFAULT",
                null,
                List.of(SYSTEM_PROFILE_ID),
                true,
                1,
                now,
                now));
        profileStore.saveSecretRef(
                new LlmConnectionSecretRefDto(SYSTEM_CONN_ID, SYSTEM_SLOT_ID, VaultConstants.LLM_API_KEY, 1, now, now));
        log.infof(
                "[SYSTEM-MIGRATION] Migrated legacy system LLM (%s/%s) into SYSTEM-scoped profile route",
                provider.get(), modelId);
    }

    private Optional<String> readSystemString(String keyName) {
        return vault.retrieveSystemKey(keyName).map(key -> {
            try (key) {
                return new String(key.get(), StandardCharsets.UTF_8);
            }
        });
    }
}
