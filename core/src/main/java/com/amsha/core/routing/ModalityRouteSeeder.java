package com.amsha.core.routing;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmConnectionSecretRefDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Opt-in modality route seeder for P7 live verification.
 *
 * <p>When enabled it converges the persisted {@code SYSTEM} routing configuration on a single
 * well-known shape: one shared OMLX connection and two model profiles sharing it:
 * <ul>
 * <li>text  → Ornith-1.5-35B-A3B-MLX-4bit (TEXT_ONLY)</li>
 * <li>image → Qwen3.8-27B-4bit (IMAGE)</li>
 * </ul>
 * bound to one {@code SYSTEM}/{@code DEFAULT} route assignment with
 * {@code orderedProfileIds = [text, image]}. The
 * {@link com.amsha.core.routing.DefaultModelRouteSelector} then picks the correct profile per
 * {@link TurnRequirements} via {@code covers()} plus MIME/size/count checks. Both profiles
 * share one connection (N:1), preserving the persisted domain model.</p>
 *
 * <p><b>No AUDIO chat profile.</b> An earlier revision of this seeder also installed
 * {@code modality-audio-nemotron} ({@code NemotronLabs-VoiceChat-11B-8bit}). OMLX cannot serve
 * that architecture at all: its {@code config.json} declares
 * {@code "model_type": "nemotron_voicechat"} and OMLX's bundled {@code mlx_lm} has no module for
 * it, so every request fails with {@code 409 Model type nemotron_voicechat not supported}. A
 * profile that is selectable but permanently broken is worse than no profile, so the seeder no
 * longer installs one. {@code retireDeprecatedProfiles} <em>disables</em> (never deletes) a
 * profile left over from an earlier run so a stale row cannot be selected. Native
 * audio-chat routing is therefore <b>BLOCKED</b>: an audio attachment matches no profile and
 * degrades to the structured text placeholder, which is the honest behaviour until the STT path
 * lands (see {@code .scratch/lc4j-capability-adoption/issues/11-p7-audio-transcription-port.md}).
 * Audio input is an execution/port concern, not a model-profile concern: Whisper is never
 * registered as an AUDIO chat profile.</p>
 *
 * <p><b>Opt-in guard.</b> Seeding requires {@code amsha.llm.modality-route-seeder.enabled}
 * <em>and</em> {@code amsha.llm.profile-routing.enabled}. Without profile routing the seeded
 * records are never read, so writing them would silently diverge persisted state from the
 * effective path. Both guards, and the vault-key precondition, are evaluated <em>before</em> any
 * mutation: the seeder never disables or rewrites a route until the replacement is already in
 * place.</p>
 *
 * <p><b>Safe activation.</b> The seeder is convergent, not insert-only. Because it is opt-in,
 * test-only, and scoped to {@code SYSTEM}, it repairs an already-persisted configuration instead
 * of stranding it: a non-{@code ACTIVE} connection is activated through the port's atomic
 * {@code activateConnection}, a disabled or stale profile is re-pointed and re-enabled, and a
 * disabled or stale route assignment is re-enabled and re-ordered.</p>
 *
 * <p><b>Install before remove.</b> Removal never precedes installation. The canonical route is
 * created or repaired <em>first</em> and only when that succeeds does the seeder retire deprecated
 * profiles and disable colliding {@code SYSTEM DEFAULT} routes. An earlier revision deleted
 * colliding routes <em>before</em> installing the replacement, so a failure in between destroyed
 * the legacy route and left zero {@code SYSTEM DEFAULT} routes. Every write is a compare-and-set
 * through the port's revision-checked update, so a concurrent edit wins and the next startup
 * reconverges.</p>
 *
 * <p><b>Non-destructive convergence.</b> Nothing is ever deleted. Colliding {@code SYSTEM DEFAULT}
 * routes (for example the legacy {@code sys-legacy-route} derived by
 * {@link SystemProfileMigration}) are <em>disabled</em>, leaving exactly one <em>enabled</em>
 * {@code SYSTEM DEFAULT} route while keeping the old row recoverable by hand. Retired model
 * profiles are disabled in place for the same reason. Startup never blocks: any failure is caught
 * and logged, and the pre-existing configuration keeps serving traffic.</p>
 */
@ApplicationScoped
public class ModalityRouteSeeder {

    private static final Logger log = Logger.getLogger(ModalityRouteSeeder.class);

    static final String SYSTEM_SCOPE = "SYSTEM";
    static final String DEFAULT_TARGET = "DEFAULT";
    static final String ACTIVE_STATE = "ACTIVE";
    static final String SLOT_API_KEY = "api_key";
    static final String CONN_ID = "omlx-modality-shared";
    static final String PROFILE_TEXT_ID = "modality-text-ornith";
    static final String PROFILE_IMAGE_ID = "modality-image-qwen";
    static final String ROUTE_ID = "modality-route-system";

    static final String MODEL_TEXT = "Ornith-1.5-35B-A3B-MLX-4bit";
    static final String MODEL_IMAGE = "Qwen3.8-27B-4bit";

    /**
     * Profiles installed by an earlier revision that must no longer be selectable. They are
     * disabled in place, never deleted, so retiring one destroys no operator data.
     */
    static final String PROFILE_AUDIO_ID = "modality-audio-nemotron";

    static final String MODEL_AUDIO = "NemotronLabs-VoiceChat-11B-8bit";
    static final List<String> DEPRECATED_PROFILE_IDS = List.of(PROFILE_AUDIO_ID);

    static final List<String> ORDERED_PROFILE_IDS = List.of(PROFILE_TEXT_ID, PROFILE_IMAGE_ID);

    static final String CAP_TEXT =
            "{\"supportedTypes\":[],\"supportedMimeTypes\":[],\"maxFileSize\":0,\"maxMediaPerRequest\":0,\"supportsInlineBase64\":false,\"supportsMediaUrls\":false}";
    static final String CAP_IMAGE =
            "{\"supportedTypes\":[\"IMAGE\"],\"supportedMimeTypes\":[\"image/png\",\"image/jpeg\",\"image/webp\",\"image/gif\"],\"maxFileSize\":10485760,\"maxMediaPerRequest\":5,\"supportsInlineBase64\":true,\"supportsMediaUrls\":false}";

    private static final String TAG = "[MODALITY-SEEDER] ";

    private final SecretVault vault;
    private final LlmProfileStorePort profileStore;

    @ConfigProperty(name = LlmConfigKeys.PROFILE_ROUTING_ENABLED, defaultValue = "false")
    boolean profileRoutingEnabled;

    @ConfigProperty(name = "amsha.llm.modality-route-seeder.enabled", defaultValue = "false")
    boolean seederEnabled;

    @Inject
    public ModalityRouteSeeder(SecretVault vault, LlmProfileStorePort profileStore) {
        this.vault = vault;
        this.profileStore = profileStore;
    }

    protected ModalityRouteSeeder() {
        this(null, null);
    }

    void onStart(@Observes @Priority(60) StartupEvent ev) {
        try {
            seedIfNeeded();
        } catch (Exception e) {
            log.warnf(
                    TAG + "Modality route seeding skipped due to error; "
                            + "profile routing will use existing routes or legacy fallback: %s",
                    LogUtil.rootMessage(e));
        }
    }

    void seedIfNeeded() {
        if (!seederEnabled) {
            log.debugf(TAG + "Seeder disabled (amsha.llm.modality-route-seeder.enabled=false); skipping");
            return;
        }
        if (!profileRoutingEnabled) {
            log.infof(TAG + "Seeder enabled but profile routing is disabled "
                    + "(amsha.llm.profile-routing.enabled=false); seeded routes would never be read. "
                    + "Skipping without touching persisted configuration.");
            return;
        }
        if (!vault.systemKeyExists(VaultConstants.LLM_API_KEY)) {
            log.infof(
                    TAG + "System vault key %s missing; skipping modality seeding without mutating routes. "
                            + "Legacy routing remains active. Complete onboarding or set the key.",
                    VaultConstants.LLM_API_KEY);
            return;
        }

        Instant now = Instant.now();
        ensureConnection(now);
        ensureProfiles(now);

        // Install the replacement FIRST. Nothing is retired or disabled until the canonical route
        // is confirmed in place, so a failure here leaves the pre-existing configuration serving
        // traffic exactly as it did before startup.
        if (!ensureRoute(now)) {
            log.warnf(
                    TAG + "Canonical route %s could not be installed or repaired; skipping profile "
                            + "retirement and collision handling. The pre-existing configuration is "
                            + "left untouched and reconvergence retries on next startup.",
                    ROUTE_ID);
            logActiveRouteState();
            return;
        }

        retireDeprecatedProfiles(now);
        disableCollidingSystemDefaultRoutes(now);
        logActiveRouteState();
    }

    /**
     * Ensures the shared OMLX connection exists, carries its {@code llm.api.key} secret
     * reference, and is in the {@code ACTIVE} state. A connection in any other state makes every
     * profile hanging off it unroutable, so it is activated through the port's atomic activation
     * rather than rewritten field by field.
     */
    private void ensureConnection(Instant now) {
        Optional<LlmConnectionDto> existing = profileStore.findConnection(CONN_ID);
        if (existing.isEmpty()) {
            profileStore.saveConnection(new LlmConnectionDto(
                    CONN_ID,
                    SYSTEM_SCOPE,
                    SYSTEM_SCOPE,
                    "system",
                    "Shared OMLX modality connection (text/image)",
                    ModelDefaults.PROVIDER_OMLX,
                    "default",
                    null,
                    "API_KEY",
                    null,
                    ACTIVE_STATE,
                    1,
                    now,
                    now));
            profileStore.saveSecretRef(
                    new LlmConnectionSecretRefDto(CONN_ID, SLOT_API_KEY, VaultConstants.LLM_API_KEY, 1, now, now));
            log.infof(
                    TAG + "Created shared OMLX connection %s (provider=%s, state=%s)",
                    CONN_ID,
                    ModelDefaults.PROVIDER_OMLX,
                    ACTIVE_STATE);
            return;
        }
        LlmConnectionDto conn = existing.get();
        if (profileStore.listSecretRefs(CONN_ID).isEmpty()) {
            profileStore.saveSecretRef(new LlmConnectionSecretRefDto(
                    CONN_ID, SLOT_API_KEY, VaultConstants.LLM_API_KEY, 1, conn.createdAt(), now));
            log.infof(TAG + "Re-attached missing secret reference %s on connection %s", SLOT_API_KEY, CONN_ID);
        }
        if (ACTIVE_STATE.equalsIgnoreCase(conn.state())) {
            return;
        }
        if (profileStore.activateConnection(CONN_ID, conn.revision())) {
            log.infof(
                    TAG + "Activated connection %s (state=%s -> %s), enabling its profiles and referencing routes",
                    CONN_ID,
                    conn.state(),
                    ACTIVE_STATE);
        } else {
            log.warnf(
                    TAG + "Could not activate connection %s at revision %d (concurrent update); "
                            + "activation retries on next startup",
                    CONN_ID,
                    conn.revision());
        }
    }

    private void ensureProfiles(Instant now) {
        ensureProfile(PROFILE_TEXT_ID, MODEL_TEXT, CAP_TEXT, now);
        ensureProfile(PROFILE_IMAGE_ID, MODEL_IMAGE, CAP_IMAGE, now);
    }

    /**
     * Disables profiles that an earlier revision of this seeder installed but that the runtime
     * cannot serve. Disabling rather than deleting keeps the retirement non-destructive and
     * idempotent: a disabled profile is invisible to
     * {@link com.amsha.core.routing.DefaultModelRouteSelector}, so the route chain can never pick
     * a model that would fail at request time. Re-enabling one is a deliberate operator action,
     * not something a restart should undo.
     */
    private void retireDeprecatedProfiles(Instant now) {
        for (String profileId : DEPRECATED_PROFILE_IDS) {
            Optional<ModelProfileDto> existing = profileStore.findModelProfile(profileId);
            if (existing.isEmpty()) {
                log.debugf(TAG + "Deprecated profile %s not present; nothing to retire", profileId);
                continue;
            }
            ModelProfileDto profile = existing.get();
            if (!profile.enabled()) {
                log.debugf(TAG + "Deprecated profile %s already disabled", profileId);
                continue;
            }
            ModelProfileDto retired = new ModelProfileDto(
                    profile.id(),
                    profile.connectionId(),
                    profile.modelId(),
                    profile.structuredOutputMode(),
                    profile.capabilityJson(),
                    profile.safeLimitsJson(),
                    false,
                    profile.revision(),
                    profile.createdAt(),
                    now);
            if (profileStore.updateModelProfile(retired, profile.revision())) {
                log.warnf(
                        TAG + "Disabled deprecated profile %s (model=%s): OMLX cannot load this architecture "
                                + "(409 Model type not supported). Native audio-chat routing is BLOCKED; "
                                + "audio input is handled by the separate STT path, not by an AUDIO chat profile.",
                        profile.id(),
                        profile.modelId());
            } else {
                log.warnf(
                        TAG + "Could not disable deprecated profile %s at revision %d (concurrent update); "
                                + "retirement retries on next startup",
                        profile.id(),
                        profile.revision());
            }
        }
    }

    /**
     * Creates the profile when absent and repairs it when present but drifted: wrong connection,
     * wrong model, stale capability contract, or disabled. Revision-checked so a concurrent
     * operator edit wins and the next startup reconverges.
     */
    private void ensureProfile(String profileId, String modelId, String capabilityJson, Instant now) {
        Optional<ModelProfileDto> existing = profileStore.findModelProfile(profileId);
        if (existing.isEmpty()) {
            profileStore.saveModelProfile(new ModelProfileDto(
                    profileId, CONN_ID, modelId, "PROMPT_DIRECTIVE", capabilityJson, null, true, 1, now, now));
            log.infof(TAG + "Created profile %s (%s) on connection %s", profileId, modelId, CONN_ID);
            return;
        }
        ModelProfileDto profile = existing.get();
        if (profile.enabled()
                && CONN_ID.equals(profile.connectionId())
                && modelId.equals(profile.modelId())
                && Objects.equals(capabilityJson, profile.capabilityJson())) {
            return;
        }
        ModelProfileDto repaired = new ModelProfileDto(
                profileId,
                CONN_ID,
                modelId,
                profile.structuredOutputMode(),
                capabilityJson,
                profile.safeLimitsJson(),
                true,
                profile.revision(),
                profile.createdAt(),
                now);
        if (profileStore.updateModelProfile(repaired, profile.revision())) {
            log.infof(
                    TAG + "Repaired profile %s (model=%s, connection=%s, enabled=true, wasEnabled=%s)",
                    profileId,
                    modelId,
                    CONN_ID,
                    profile.enabled());
        } else {
            log.warnf(
                    TAG + "Could not repair profile %s at revision %d (concurrent update); "
                            + "repair retries on next startup",
                    profileId,
                    profile.revision());
        }
    }

    /**
     * Disables every {@code SYSTEM DEFAULT} route other than the canonical one so the persisted
     * configuration resolves to exactly one enabled default, without destroying anything.
     *
     * <p><b>Non-destructive by construction.</b> An earlier revision called
     * {@code deleteRouteAssignment} here, which ran <em>before</em> the replacement was installed:
     * if {@code ensureRoute} then failed, the legacy route had already been destroyed and the
     * system was left with zero {@code SYSTEM DEFAULT} routes. This revision inverts the order
     * (see {@link #seedIfNeeded()}) and replaces deletion with a disable, so the worst case is a
     * leftover row an operator can re-enable by hand rather than unrecoverable data loss.</p>
     *
     * <p>The write is a compare-and-set: the port's revision-checked
     * {@code updateRouteAssignment} rejects the update when the row changed underneath us, so a
     * concurrent operator edit wins and the next startup reconverges. Already-disabled routes are
     * left alone so a converged configuration does not churn revisions.</p>
     */
    private void disableCollidingSystemDefaultRoutes(Instant now) {
        for (ModelRouteAssignmentDto route : profileStore.listRoutesByScope(SYSTEM_SCOPE, SYSTEM_SCOPE)) {
            if (!DEFAULT_TARGET.equals(route.targetKind()) || ROUTE_ID.equals(route.id())) {
                continue;
            }
            if (!route.enabled()) {
                log.debugf(
                        TAG + "Colliding SYSTEM DEFAULT route %s already disabled; leaving it untouched", route.id());
                continue;
            }
            ModelRouteAssignmentDto disabled = new ModelRouteAssignmentDto(
                    route.id(),
                    route.scopeType(),
                    route.scopeId(),
                    route.targetKind(),
                    route.targetId(),
                    route.orderedProfileIds(),
                    false,
                    route.revision(),
                    route.createdAt(),
                    now);
            if (profileStore.updateRouteAssignment(disabled, route.revision())) {
                log.infof(
                        TAG + "Disabled colliding SYSTEM DEFAULT route %s (revision %d) to keep exactly 1 "
                                + "enabled SYSTEM DEFAULT route (opt-in seeder enabled). The row is retained; "
                                + "re-enable it manually to restore legacy routing.",
                        route.id(),
                        route.revision());
            } else {
                log.warnf(
                        TAG + "Could not disable colliding SYSTEM DEFAULT route %s at revision %d "
                                + "(concurrent update); it stays enabled and reconvergence retries on next startup",
                        route.id(),
                        route.revision());
            }
        }
    }

    /**
     * Installs the canonical route when absent and repairs it when present but disabled, drifted,
     * or re-scoped. One retry with a fresh revision absorbs a concurrent edit.
     *
     * @return {@code true} when the canonical route is present and active on return. A
     *         {@code false} result means the replacement is <em>not</em> in place, so the caller
     *         must not retire or disable anything that is currently serving traffic.
     */
    private boolean ensureRoute(Instant now) {
        Optional<ModelRouteAssignmentDto> existing = profileStore.findRouteAssignment(ROUTE_ID);
        if (existing.isEmpty()) {
            profileStore.saveRouteAssignment(new ModelRouteAssignmentDto(
                    ROUTE_ID,
                    SYSTEM_SCOPE,
                    SYSTEM_SCOPE,
                    DEFAULT_TARGET,
                    null,
                    ORDERED_PROFILE_IDS,
                    true,
                    1,
                    now,
                    now));
            log.infof(
                    TAG + "Created modality route %s: text→%s, image→%s (shared connection %s); "
                            + "no AUDIO profile: native audio-chat is BLOCKED on OMLX",
                    ROUTE_ID,
                    MODEL_TEXT,
                    MODEL_IMAGE,
                    CONN_ID);
            return true;
        }
        ModelRouteAssignmentDto route = existing.get();
        if (route.enabled()
                && DEFAULT_TARGET.equals(route.targetKind())
                && SYSTEM_SCOPE.equals(route.scopeType())
                && SYSTEM_SCOPE.equals(route.scopeId())
                && ORDERED_PROFILE_IDS.equals(route.orderedProfileIds())) {
            return true;
        }
        if (writeRoute(route, now)) {
            log.infof(
                    TAG + "Repaired modality route %s (enabled=true, orderedProfileIds=%s, wasEnabled=%s, wasOrder=%s)",
                    ROUTE_ID,
                    ORDERED_PROFILE_IDS,
                    route.enabled(),
                    route.orderedProfileIds());
            return true;
        }
        return false;
    }

    private boolean writeRoute(ModelRouteAssignmentDto current, Instant now) {
        if (updateRoute(current, now)) {
            return true;
        }
        Optional<ModelRouteAssignmentDto> fresh = profileStore.findRouteAssignment(ROUTE_ID);
        return fresh.isPresent() && updateRoute(fresh.get(), now);
    }

    private boolean updateRoute(ModelRouteAssignmentDto current, Instant now) {
        ModelRouteAssignmentDto repaired = new ModelRouteAssignmentDto(
                ROUTE_ID,
                SYSTEM_SCOPE,
                SYSTEM_SCOPE,
                DEFAULT_TARGET,
                null,
                ORDERED_PROFILE_IDS,
                true,
                current.revision(),
                current.createdAt(),
                now);
        boolean updated = profileStore.updateRouteAssignment(repaired, current.revision());
        if (!updated) {
            log.warnf(
                    TAG + "Could not repair modality route %s at revision %d (concurrent update)",
                    ROUTE_ID,
                    current.revision());
        }
        return updated;
    }

    /**
     * Emits the post-condition so the routing configuration is verifiable from logs alone, and
     * surfaces a warning when the single-enabled-SYSTEM-DEFAULT-route invariant does not hold.
     *
     * <p>The invariant counts <em>enabled</em> default routes, not total rows: collision handling
     * disables rather than deletes, so a machine that was migrated from legacy routing legitimately
     * keeps a disabled {@code sys-legacy-route} row alongside the canonical one.</p>
     */
    private void logActiveRouteState() {
        List<ModelRouteAssignmentDto> routes = profileStore.listRoutesByScope(SYSTEM_SCOPE, SYSTEM_SCOPE);
        long defaultTotal = routes.stream()
                .filter(r -> DEFAULT_TARGET.equals(r.targetKind()))
                .count();
        long defaultEnabled = routes.stream()
                .filter(r -> DEFAULT_TARGET.equals(r.targetKind()) && r.enabled())
                .count();
        Optional<ModelRouteAssignmentDto> canonical = profileStore.findRouteAssignment(ROUTE_ID);

        if (defaultEnabled != 1 || canonical.isEmpty() || !canonical.get().enabled()) {
            log.warnf(
                    TAG + "SYSTEM DEFAULT route invariant violated: total=%d enabled=%d canonical=%s; "
                            + "routing falls back to the legacy path until this converges",
                    defaultTotal,
                    defaultEnabled,
                    canonical.map(r -> "enabled=" + r.enabled()).orElse("absent"));
            return;
        }
        log.infof(
                TAG + "ACTIVE SYSTEM DEFAULT route id=%s scope=%s/%s targetKind=%s enabled=true "
                        + "orderedProfileIds=%s connection=%s routeIds=[profile:%s, profile:%s] "
                        + "models=[text=%s, image=%s] audioProfile=none (native audio-chat BLOCKED on OMLX)",
                ROUTE_ID,
                SYSTEM_SCOPE,
                SYSTEM_SCOPE,
                DEFAULT_TARGET,
                canonical.get().orderedProfileIds(),
                CONN_ID,
                PROFILE_TEXT_ID,
                PROFILE_IMAGE_ID,
                MODEL_TEXT,
                MODEL_IMAGE);
    }
}
