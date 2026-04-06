package com.arcclaw.core.pipeline.mock;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.IntentClassifier;
import io.quarkus.test.Mock;

@Mock
@ApplicationScoped
@Priority(1000)
public class MockIntentClassifier implements IntentClassifier {
    @Override
    public String classify(String userMessage) {
        return "coordinator"; // Default fallback for tests
    }
}
