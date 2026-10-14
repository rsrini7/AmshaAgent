package com.amsha.core.classifier.selfhosted;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.amsha.classifier.api.StateProjectionPolicy;
import com.amsha.core.classifier.wire.WireProfile;

/**
 * Configuration for a self-hosted loopback decision provider (§7.5).
 * Enforces unconditional loopback (127.0.0.1) validation at startup/instantiation time.
 *
 * @param enabled whether the provider is enabled (defaults to false; disabled by default)
 * @param endpoint HTTP/HTTPS endpoint URI (strictly validated to 127.0.0.1)
 * @param loopbackToken optional bearer token or authentication header value for loopback communication
 * @param credentialHeaderStyle header format used when the token is present
 * @param modelId optional model identifier for the System One {@code model} field. Blank by default:
 *     a self-hosted server selects its own checkpoint, and sending a hosted vendor's alias would
 *     mislead it. Set this only to pin a specific self-hosted model.
 * @param wireProfile how this endpoint expects the {@code state} field to be rendered. Defaults to
 *     {@link WireProfile#JSON_OBJECT}, which is what the Jev-wire-compatible self-hosted servers
 *     (Laya, Kev) accept. Set {@link WireProfile#PROSE} for a CLM endpoint, whose contrastive heads
 *     were trained on prose and score a JSON blob worse without rejecting it.
 * @param timeout request execution timeout
 * @param defaultProjectionPolicy default state projection and redaction policy if not specified on request
 */
public record SelfHostedProviderConfig(
        boolean enabled,
        URI endpoint,
        String loopbackToken,
        CredentialHeaderStyle credentialHeaderStyle,
        String modelId,
        WireProfile wireProfile,
        Duration timeout,
        StateProjectionPolicy defaultProjectionPolicy) {

    public enum CredentialHeaderStyle {
        AMSHA_LOOPBACK_TOKEN,
        AUTHORIZATION_BEARER
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public SelfHostedProviderConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(credentialHeaderStyle, "credentialHeaderStyle");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }
        // A null profile would silently fall back to whichever dialect the mapper happened to default
        // to, so it is resolved here rather than left to the call site.
        wireProfile = wireProfile == null ? WireProfile.JSON_OBJECT : wireProfile;
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
                + ", modelId=" + (modelId != null && !modelId.isBlank() ? modelId : "none")
                + ", wireProfile=" + wireProfile
                + ", timeout=" + timeout
                + ", defaultProjectionPolicy=" + defaultProjectionPolicy + "]";
    }

    public static final class Builder {
        private boolean enabled = false;
        // The System One route, matching the payload the mapper emits. This port is a placeholder: the
        // real port is whichever the loopback wrapper binds.
        private URI endpoint = URI.create("http://127.0.0.1:8088/v1/systemone");
        private String loopbackToken = null;
        private CredentialHeaderStyle credentialHeaderStyle = CredentialHeaderStyle.AMSHA_LOOPBACK_TOKEN;
        private String modelId = null;
        private WireProfile wireProfile = WireProfile.JSON_OBJECT;
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

        public Builder credentialHeaderStyle(CredentialHeaderStyle credentialHeaderStyle) {
            this.credentialHeaderStyle = Objects.requireNonNull(credentialHeaderStyle, "credentialHeaderStyle");
            return this;
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder wireProfile(WireProfile wireProfile) {
            this.wireProfile = wireProfile;
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
            return new SelfHostedProviderConfig(
                    enabled,
                    endpoint,
                    loopbackToken,
                    credentialHeaderStyle,
                    modelId,
                    wireProfile,
                    timeout,
                    defaultProjectionPolicy);
        }
    }
}
