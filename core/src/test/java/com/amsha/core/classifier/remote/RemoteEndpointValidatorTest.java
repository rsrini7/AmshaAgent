package com.amsha.core.classifier.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;

import com.amsha.classifier.api.RetentionPolicy;

import org.junit.jupiter.api.Test;

class RemoteEndpointValidatorTest {

    @Test
    void allowsValidHttpsRemoteEndpoint() {
        URI valid = URI.create("https://decision.internal.example.com/v1/evaluate");
        URI validated = RemoteEndpointValidator.validateRemoteEndpoint(valid);
        assertEquals(valid, validated);

        URI validWithPort = URI.create("https://decision.corp.net:8443/evaluate");
        assertEquals(validWithPort, RemoteEndpointValidator.validateRemoteEndpoint(validWithPort));
    }

    @Test
    void allowsLoopbackForTestingAndStubs() {
        URI loopbackIpv4 = URI.create("http://127.0.0.1:8080/evaluate");
        assertEquals(loopbackIpv4, RemoteEndpointValidator.validateRemoteEndpoint(loopbackIpv4));

        URI localhost = URI.create("http://localhost:8080/evaluate");
        assertEquals(localhost, RemoteEndpointValidator.validateRemoteEndpoint(localhost));

        URI loopbackIpv6 = URI.create("http://[::1]:8080/evaluate");
        assertEquals(loopbackIpv6, RemoteEndpointValidator.validateRemoteEndpoint(loopbackIpv6));
    }

    @Test
    void rejectsNonLoopbackHttpScheme() {
        URI plainHttp = URI.create("http://decision.internal.example.com/v1/evaluate");
        SecurityException ex =
                assertThrows(SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(plainHttp));
        assertTrue(ex.getMessage().contains("HTTPS"), ex.getMessage());
    }

    @Test
    void rejectsWildcardBinding() {
        URI wildcardIpv4 = URI.create("https://0.0.0.0:8443/evaluate");
        SecurityException ex = assertThrows(
                SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(wildcardIpv4));
        assertTrue(ex.getMessage().contains("Wildcard binding is strictly forbidden"), ex.getMessage());

        URI wildcardIpv6 = URI.create("https://[::]:8443/evaluate");
        assertThrows(SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(wildcardIpv6));
    }

    @Test
    void rejectsEndpointUserInformation() {
        URI withUserInfo = URI.create("https://user:password@decision.internal.example.com/v1/evaluate");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(withUserInfo));
        assertTrue(ex.getMessage().contains("user information"), ex.getMessage());
    }

    @Test
    void rejectsBlockedLiteralAddressesSsrf() {
        URI privateTen = URI.create("https://10.0.0.1:8443/evaluate");
        assertThrows(SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(privateTen));

        URI privateOneNineTwo = URI.create("https://192.168.1.1:8443/evaluate");
        assertThrows(SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(privateOneNineTwo));

        URI privateOneSevenTwo = URI.create("https://172.16.0.1:8443/evaluate");
        assertThrows(SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(privateOneSevenTwo));

        URI linkLocalMetadata = URI.create("https://169.254.169.254:8443/evaluate");
        assertThrows(SecurityException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(linkLocalMetadata));
    }

    @Test
    void rejectsNullOrMalformedEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(null));

        URI nonHttp = URI.create("ftp://decision.internal.example.com/evaluate");
        assertThrows(IllegalArgumentException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(nonHttp));

        URI invalidPort = URI.create("https://decision.internal.example.com:70000/evaluate");
        assertThrows(IllegalArgumentException.class, () -> RemoteEndpointValidator.validateRemoteEndpoint(invalidPort));
    }

    @Test
    void configEnforcesDataGovernanceAndTls() {
        // Training forbidden
        var trainingBuilder = RemoteProviderConfig.builder()
                .endpoint("https://decision.internal.example.com/evaluate")
                .retentionPolicy(new RetentionPolicy(Duration.ZERO, true, false));
        assertThrows(IllegalArgumentException.class, trainingBuilder::build);

        // Tenant isolation required
        var isolationBuilder = RemoteProviderConfig.builder()
                .endpoint("https://decision.internal.example.com/evaluate")
                .tenantIsolation(false);
        assertThrows(IllegalArgumentException.class, isolationBuilder::build);

        // Retention policy required (cannot be null)
        var nullRetentionBuilder = RemoteProviderConfig.builder()
                .endpoint("https://decision.internal.example.com/evaluate")
                .retentionPolicy(null);
        assertThrows(NullPointerException.class, nullRetentionBuilder::build);

        // Insecure endpoint rejected
        var insecureBuilder = RemoteProviderConfig.builder().endpoint("http://decision.internal.example.com/evaluate");
        assertThrows(SecurityException.class, insecureBuilder::build);
    }

    @Test
    void configToStringRedactsApiKey() {
        String testToken = "remote-" + "api-secret-key-12345";
        var config = RemoteProviderConfig.builder()
                .endpoint("https://decision.internal.example.com/evaluate")
                .timeout(Duration.ofSeconds(3))
                .apiKey(testToken)
                .build();

        String str = config.toString();
        assertFalse(str.contains("api-secret-key-12345"), "Cleartext API key must not appear in toString(): " + str);
        assertTrue(str.contains("hasApiKey=true"), "toString() must indicate API key presence: " + str);
    }
}
