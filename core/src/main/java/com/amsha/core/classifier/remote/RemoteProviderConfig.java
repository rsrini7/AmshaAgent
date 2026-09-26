package com.amsha.core.classifier.remote;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.RetentionPolicy;
import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.persistence.api.LlmConnectionDto;

/**
 * Configuration and data governance controls for a remote decision provider (§3, §7.6).
 * Enforces TLS, data residency, zero-training, tenant-isolation, and connection/proxy constraints.
 * Redacts credentials in {@link #toString()}.
 *
 * @param enabled whether the provider is enabled (defaults to false; disabled by default)
 * @param endpoint HTTPS endpoint URI (strictly validated against non-TLS and SSRF addresses)
 * @param apiKey optional API key or bearer token for remote authentication
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
        private URI endpoint = URI.create("https://decision-plane.amsha.internal/v1/evaluate");
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
