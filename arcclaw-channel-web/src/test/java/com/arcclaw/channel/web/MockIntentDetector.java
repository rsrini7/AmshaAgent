package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.IntentDetector;

/**
 * Mock implementation to satisfy CDI validation during unit tests
 * for arcclaw-channel-web.
 */
@ApplicationScoped
public class MockIntentDetector implements IntentDetector {

    @Override
    public String classify(String userMessage) {
        return "coordinator";
    }
}
