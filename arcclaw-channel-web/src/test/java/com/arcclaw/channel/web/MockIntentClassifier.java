package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.IntentClassifier;

/**
 * Mock implementation to satisfy CDI validation during unit tests
 * for arcclaw-channel-web.
 */
@ApplicationScoped
public class MockIntentClassifier implements IntentClassifier {

    @Override
    public String classify(String userMessage) {
        return "coordinator";
    }
}
