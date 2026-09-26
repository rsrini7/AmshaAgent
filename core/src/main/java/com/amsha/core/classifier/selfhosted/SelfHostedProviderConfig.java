package com.amsha.core.classifier.selfhosted;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.StateProjectionPolicy;

/**
 * Configuration for a self-hosted loopback decision provider (§7.5).
 * Enforces unconditional loopback (127.0.0.1) validation at startup/instantiation time.
 *
 * @param enabled whether the provider is enabled (defaults to false; disabled by default)
 * @param endpoint HTTP/HTTPS endpoint URI (strictly validated to 127.0.0.1)
 * @param loopbackToken optional bearer token or authentication header value for loopback communication
 * @param timeout request execution timeout
 * @param defaultProjectionPolicy default state projection and redaction policy if not specified on request
 */
public record SelfHostedProviderConfig(
        boolean enabled,
        URI endpoint,
        String loopbackToken,
        Duration timeout,
        StateProjectionPolicy defaultProjectionPolicy) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public SelfHostedProviderConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }
        // Unconditional loopback validation: strict 127.0.0.1 requirement cannot be bypassed
        SelfHostedEndpointValidator.validateLoopbackEndpoint(endpoint);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> optionalLoopbackToken() {
        return loopbackToken != null && !loopbackToken.isBlank() ? Optional.of(loopbackToken.trim()) : Optional.empty();
    }

    @Override
    public String toString() {
        return "SelfHostedProviderConfig[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", hasLoopbackToken=" + (loopbackToken != null && !loopbackToken.isBlank())
                + ", timeout=" + timeout
                + ", defaultProjectionPolicy=" + defaultProjectionPolicy + "]";
    }

    public static final class Builder {
        private boolean enabled = false;
        private URI endpoint = URI.create("http://127.0.0.1:8088/v1/evaluate");
        private String loopbackToken = null;
        private Duration timeout = DEFAULT_TIMEOUT;
        private StateProjectionPolicy defaultProjectionPolicy = null;

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

        public Builder loopbackToken(String loopbackToken) {
            this.loopbackToken = loopbackToken;
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

        public SelfHostedProviderConfig build() {
            return new SelfHostedProviderConfig(enabled, endpoint, loopbackToken, timeout, defaultProjectionPolicy);
        }
    }
}
