package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.IntentDetector;

@ApplicationScoped
public class MockIntentDetector implements IntentDetector {

    @Override
    public String classify(String userMessage) {
        return "coordinator";
    }
}
