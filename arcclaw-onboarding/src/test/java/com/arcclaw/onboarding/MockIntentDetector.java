package com.arcclaw.onboarding;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.IntentDetector;

@ApplicationScoped
public class MockIntentDetector implements IntentDetector {

    @Override
    public String classify(String userMessage) {
        return "coordinator";
    }
}
