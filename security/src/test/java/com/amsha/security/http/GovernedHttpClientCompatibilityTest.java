package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;

import org.junit.jupiter.api.Test;

class GovernedHttpClientCompatibilityTest {

    @Test
    void wrapsCorePolicyExceptionInSecurityCompatibilityType() {
        GovernedHttpClient client = GovernedHttpClient.builder(new DenyPolicy()).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://provider.example/evaluate"))
                .GET()
                .build();

        OutboundHttpException exception = assertThrows(
                OutboundHttpException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, exception.category());
    }

    private static final class DenyPolicy extends OutboundHttpPolicyService {
        @Override
        public OutboundPolicyDecision evaluate(
                String url,
                OutboundPurpose purpose,
                LlmConnectionDto connection,
                String actorUserId,
                boolean preApproved) {
            return OutboundPolicyDecision.deny("test denial", purpose, actorUserId);
        }
    }
}
