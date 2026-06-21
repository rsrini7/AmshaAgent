package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.IntentDetector;

/**
 * Mock implementation to satisfy CDI validation during unit tests
 * for amsha-channel-web.
 */
@ApplicationScoped
public class MockIntentDetector implements IntentDetector {

    @Override
    public String classify(String userMessage) {
        return "coordinator";
    }
}
