package com.amsha.core.classifier.selfhosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class SelfHostedEndpointValidatorTest {

    @Test
    void allowsValidLoopbackEndpoint() {
        URI valid = URI.create("http://127.0.0.1:8088/v1/evaluate");
        URI validated = SelfHostedEndpointValidator.validateLoopbackEndpoint(valid);
        assertEquals(valid, validated);

        URI validHttps = URI.create("https://127.0.0.1:8443/evaluate");
        assertEquals(validHttps, SelfHostedEndpointValidator.validateLoopbackEndpoint(validHttps));
    }

    @Test
    void rejectsEndpointUserInformation() {
        URI endpointWithCredentials = URI.create("http://user:password@127.0.0.1:8088/v1/evaluate");
        assertThrows(
                IllegalArgumentException.class,
                () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(endpointWithCredentials));
    }

    @Test
    void rejectsWildcardBindingZeroZeroZeroZero() {
        URI wildcard = URI.create("http://0.0.0.0:8088/v1/evaluate");
        SecurityException ex = assertThrows(
                SecurityException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(wildcard));
        assertTrue(ex.getMessage().contains("0.0.0.0"), ex.getMessage());
    }

    @Test
    void rejectsLocalhostBindingToAvoidDnsRebinding() {
        URI localhost = URI.create("http://localhost:8088/v1/evaluate");
        SecurityException ex = assertThrows(
                SecurityException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(localhost));
        assertTrue(ex.getMessage().contains("localhost"), ex.getMessage());
    }

    @Test
    void rejectsExternalOrNonLoopbackHost() {
        URI externalIp = URI.create("http://192.168.1.50:8088/evaluate");
        assertThrows(SecurityException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(externalIp));

        URI domain = URI.create("http://example.com:8088/evaluate");
        assertThrows(SecurityException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(domain));
    }

    @Test
    void rejectsNullOrMalformedEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(null));

        URI nonHttp = URI.create("ftp://127.0.0.1:8088/evaluate");
        assertThrows(
                IllegalArgumentException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(nonHttp));

        URI noPort = URI.create("http://127.0.0.1/evaluate");
        assertThrows(
                IllegalArgumentException.class, () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(noPort));

        URI invalidPort = URI.create("http://127.0.0.1:0/evaluate");
        assertThrows(
                IllegalArgumentException.class,
                () -> SelfHostedEndpointValidator.validateLoopbackEndpoint(invalidPort));
    }

    @Test
    void configStartupFailsClosedOnWildcardBinding() {
        // Acceptance criterion: Local-only startup fails closed when binding is not 127.0.0.1, including 0.0.0.0
        var builder = SelfHostedProviderConfig.builder()
                .endpoint("http://0.0.0.0:8088/v1/evaluate")
                .timeout(Duration.ofSeconds(2));

        assertThrows(SecurityException.class, builder::build);
    }

    @Test
    void configStartupSucceedsOnExplicitLoopback() {
        var config = SelfHostedProviderConfig.builder()
                .endpoint("http://127.0.0.1:8088/v1/evaluate")
                .timeout(Duration.ofSeconds(3))
                .loopbackToken("test-token")
                .build();

        assertEquals(URI.create("http://127.0.0.1:8088/v1/evaluate"), config.endpoint());
        assertEquals("test-token", config.optionalLoopbackToken().orElseThrow());
    }

    @Test
    void configToStringRedactsLoopbackToken() {
        String testToken = "loopback-" + "secret-token-xyz";
        var config = SelfHostedProviderConfig.builder()
                .endpoint("http://127.0.0.1:8088/v1/evaluate")
                .timeout(Duration.ofSeconds(3))
                .loopbackToken(testToken)
                .build();

        String str = config.toString();
        assertFalse(str.contains(testToken), "Cleartext token must not appear in toString(): " + str);
        assertTrue(str.contains("hasLoopbackToken=true"), "toString() must indicate token presence: " + str);
    }
}
