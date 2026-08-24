package com.amsha.llm.api;

/**
 * Thrown when a resolved connection base URL is denied by the connection's outbound-network policy.
 * Carries only a safe (non-secret) message.
 */
public class NetworkPolicyViolation extends RuntimeException {

    public NetworkPolicyViolation(String message) {
        super(message);
    }
}
