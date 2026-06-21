package com.amsha.core.pipeline.mock;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.IntentDetector;

import io.quarkus.test.Mock;

@Mock
@ApplicationScoped
@Priority(1000)
public class MockIntentDetector implements IntentDetector {
    @Override
    public String classify(String userMessage) {
        return "coordinator"; // Default fallback for tests
    }
}
