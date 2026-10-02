package com.amsha.core.classifier.remote;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.RetentionPolicy;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.persistence.api.LlmConnectionDto;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration and data governance controls for a remote decision provider (§3, §7.6).
 * Enforces TLS, data residency, zero-training, tenant-isolation, and connection/proxy constraints.
 * Redacts credentials in {@link #toString()} and never serializes them: {@code apiKey} is
 * {@code WRITE_ONLY} for Jackson, so no serializer (telemetry, audit, REST) can emit it.
 *
 * @param enabled whether the provider is enabled (defaults to false; disabled by default)
 * @param endpoint HTTPS endpoint URI (strictly validated against non-TLS and SSRF addresses)
 * @param modelId model identifier sent in the System One {@code model} field. Required and
 *     deliberately without a default: the contract makes {@code model} mandatory, and inventing one
 *     here would silently substitute a floating vendor alias for the versioned id an operator meant
 *     to pin, defeating the provenance {@code DecisionMetadata} reports back. Never serialize a
 *     hosted vendor's alias to a self-hosted server: that lane has its own configuration and omits
 *     the field instead.
 * @param apiKey optional API key or bearer token for remote authentication; bindable from configuration
 *     but never serialized
 * @param timeout request execution timeout
 * @param defaultProjectionPolicy default state projection and redaction policy
 * @param dataLocation declared geographic/cloud data location (e.g. "US_EAST", "REMOTE_HOSTED")
 * @param retentionPolicy data retention policy (strictly required; training must be forbidden)
 * @param tenantIsolation whether tenant isolation is strictly enforced
 * @param connection outbound LLM connection context for per-connection network/proxy governance
 */
public record RemoteProviderConfig(
        boolean enabled,
        URI endpoint,

        String modelId,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String apiKey,

        Duration timeout,
        StateProjectionPolicy defaultProjectionPolicy,
        String dataLocation,
        RetentionPolicy retentionPolicy,
        boolean tenantIsolation,
        LlmConnectionDto connection) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final String DEFAULT_NETWORK_PROFILE = "{\"egressMode\":\"ALLOW_ALL\"}";

    public RemoteProviderConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }
        RemoteEndpointValidator.validateRemoteEndpoint(endpoint);
        if (dataLocation == null || dataLocation.isBlank()) {
            throw new IllegalArgumentException("dataLocation must not be blank");
        }
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        if (retentionPolicy.allowTrainingUse()) {
            throw new IllegalArgumentException(
                    "Provider training is strictly forbidden for remote decision evaluation");
        }
        if (!tenantIsolation) {
            throw new IllegalArgumentException("Tenant isolation must be true for remote decision evaluation");
        }
        Objects.requireNonNull(connection, "connection");
        // Checked last on purpose: the governance rejections above must keep precedence, so a config
        // that violates both a governance rule and this one still reports the governance problem.
        //
        // This is the only rule for "no model" on the remote lane, and it fails closed. It matches
        // DecisionProviderSelector, which refuses an enabled remote slot with no model rather than
        // defaulting one, and DecisionProviderConfig.Slot#model(), which documents that requirement.
        // A default here would be a second, contradictory rule: the alias it would invent tracks
        // whatever release the vendor currently designates, so two calls a month apart could report
        // different models from one configuration.
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank; the remote System One contract"
                    + " requires a 'model' field, and a floating alias is not a substitute for the"
                    + " versioned id the deployment meant to pin");
        }
        modelId = modelId.trim();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static LlmConnectionDto defaultConnection(URI endpoint, String networkProfileJson) {
        String url = endpoint != null ? endpoint.toString() : null;
        String profileJson = (networkProfileJson != null && !networkProfileJson.isBlank())
                ? networkProfileJson
                : DEFAULT_NETWORK_PROFILE;
        return new LlmConnectionDto(
                "conn-remote-decision",
                "SYSTEM",
                "decision-plane",
                "system",
                "Remote Decision Provider Connection",
                "remote-decision",
                "default",
                url,
                "bearer",
                profileJson,
                "ACTIVE",
                1,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    public Optional<String> optionalApiKey() {
        return apiKey != null && !apiKey.isBlank() ? Optional.of(apiKey.trim()) : Optional.empty();
    }

    @Override
    public String toString() {
        return "RemoteProviderConfig[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", modelId=" + modelId
                + ", hasApiKey=" + (apiKey != null && !apiKey.isBlank())
                + ", timeout=" + timeout
                + ", defaultProjectionPolicy=" + defaultProjectionPolicy
                + ", dataLocation=" + dataLocation
                + ", retentionPolicy=" + retentionPolicy
                + ", tenantIsolation=" + tenantIsolation
                + ", connectionId=" + connection.id() + "]";
    }

    public static final class Builder {
        private boolean enabled = false;
        // The System One route. The host remains a placeholder — a caller must supply a real endpoint —
        // but the path must match the contract the mapper emits, or the default silently posts a
        // System One payload to the old /v1/evaluate route.
        private URI endpoint = URI.create("https://decision-plane.amsha.internal/v1/systemone");
        // No default: the record rejects a blank model, so a caller that forgets it gets a clear
        // failure instead of a silent, floating vendor alias.
        private String modelId = null;
        private String apiKey = null;
        private Duration timeout = DEFAULT_TIMEOUT;
        private StateProjectionPolicy defaultProjectionPolicy = null;
        private String dataLocation = "REMOTE_HOSTED";
        private RetentionPolicy retentionPolicy = new RetentionPolicy(Duration.ZERO, false, false);
        private boolean tenantIsolation = true;
        private LlmConnectionDto connection = null;
        private String networkProfileJson = null;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            return this;
        }

        public Builder endpoint(String endpointUri) {
            Objects.requireNonNull(endpointUri, "endpointUri");
            this.endpoint = URI.create(endpointUri);
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder defaultProjectionPolicy(StateProjectionPolicy defaultProjectionPolicy) {
            this.defaultProjectionPolicy = defaultProjectionPolicy;
            return this;
        }

        public Builder dataLocation(String dataLocation) {
            this.dataLocation = dataLocation;
            return this;
        }

        public Builder retentionPolicy(RetentionPolicy retentionPolicy) {
            this.retentionPolicy = retentionPolicy;
            return this;
        }

        public Builder tenantIsolation(boolean tenantIsolation) {
            this.tenantIsolation = tenantIsolation;
            return this;
        }

        public Builder connection(LlmConnectionDto connection) {
            this.connection = connection;
            return this;
        }

        public Builder networkProfileJson(String networkProfileJson) {
            this.networkProfileJson = networkProfileJson;
            return this;
        }

        public RemoteProviderConfig build() {
            LlmConnectionDto effectiveConnection = connection;
            if (effectiveConnection == null) {
                effectiveConnection = defaultConnection(endpoint, networkProfileJson);
            }
            return new RemoteProviderConfig(
                    enabled,
                    endpoint,
                    modelId,
                    apiKey,
                    timeout,
                    defaultProjectionPolicy,
                    dataLocation,
                    retentionPolicy,
                    tenantIsolation,
                    effectiveConnection);
        }
    }
}
