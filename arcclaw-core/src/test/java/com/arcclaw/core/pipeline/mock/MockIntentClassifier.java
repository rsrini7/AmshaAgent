package com.arcclaw.core.pipeline.mock;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.IntentClassifier;

@io.quarkus.test.Mock
@ApplicationScoped
@jakarta.annotation.Priority(1000)
public class MockIntentClassifier implements IntentClassifier {
    @Override
    public String classify(String userMessage) {
        return "coordinator"; // Default fallback for tests
    }
}
