package com.amsha.security.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.UnaryOperator;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.GovernedHttpDnsPinning;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;

/**
 * Backwards-compatibility wrapper for {@link com.amsha.core.security.http.GovernedHttpClient}.
 */
public final class GovernedHttpClient {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 30_000;
    public static final int MAX_REDIRECT_HOPS = 5;

    private final com.amsha.core.security.http.GovernedHttpClient delegate;

    GovernedHttpClient(com.amsha.core.security.http.GovernedHttpClient delegate) {
        this.delegate = delegate;
    }

    public static Builder builder(OutboundHttpPolicyService policyService) {
        return new Builder(com.amsha.core.security.http.GovernedHttpClient.builder(policyService));
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return delegate.send(request, bodyHandler);
        } catch (OutboundHttpException e) {
            if (e instanceof com.amsha.security.http.OutboundHttpException ohe) {
                throw ohe;
            }
            throw new com.amsha.security.http.OutboundHttpException(
                    com.amsha.security.http.OutboundHttpException.Category.valueOf(
                            e.category().name()),
                    e.getMessage(),
                    e.getCause());
        }
    }

    public com.amsha.core.security.http.GovernedHttpClient unwrap() {
        return delegate;
    }

    public static boolean isLocalhost(String host) {
        return com.amsha.core.security.http.GovernedHttpClient.isLocalhost(host);
    }

    public static boolean isIpv4MappedAddress(byte[] bytes) {
        return GovernedHttpDnsPinning.isIpv4MappedAddress(bytes);
    }

    public static final class Builder {
        private final com.amsha.core.security.http.GovernedHttpClient.Builder delegateBuilder;

        private Builder(com.amsha.core.security.http.GovernedHttpClient.Builder delegateBuilder) {
            this.delegateBuilder = delegateBuilder;
        }

        public Builder vault(SecretVault vault) {
            delegateBuilder.vault(vault);
            return this;
        }

        public Builder purpose(OutboundPurpose purpose) {
            delegateBuilder.purpose(purpose);
            return this;
        }

        public Builder connection(LlmConnectionDto connection) {
            delegateBuilder.connection(connection);
            return this;
        }

        public Builder actor(String actorUserId) {
            delegateBuilder.actor(actorUserId);
            return this;
        }

        public Builder preApproved(boolean preApproved) {
            delegateBuilder.preApproved(preApproved);
            return this;
        }

        public Builder policyUriMapper(UnaryOperator<URI> policyUriMapper) {
            delegateBuilder.policyUriMapper(policyUriMapper);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            delegateBuilder.connectTimeout(connectTimeout);
            return this;
        }

        public Builder pinnedAddress(InetAddress pinnedAddress) {
            delegateBuilder.pinnedAddress(pinnedAddress);
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            delegateBuilder.httpClient(httpClient);
            return this;
        }

        public GovernedHttpClient build() {
            return new GovernedHttpClient(delegateBuilder.build());
        }
    }
}
