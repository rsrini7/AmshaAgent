package com.arcclaw.onboarding;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.IntentClassifier;

@ApplicationScoped
public class MockIntentClassifier implements IntentClassifier {

    @Override
    public String classify(String userMessage) {
        return "coordinator";
    }
}
