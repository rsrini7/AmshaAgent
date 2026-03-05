package com.arcclaw.core.pipeline.mock;

import com.arcclaw.channel.api.IntentClassifier;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

@Mock
@ApplicationScoped
public class MockIntentClassifier implements IntentClassifier {
    @Override
    public String classify(String userMessage) {
        return "coordinator"; // Default fallback for tests
    }
}