package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.core.llm.LlmValidationException;
import com.amsha.core.llm.LlmValidationGateway;

import io.quarkus.test.Mock;

@Mock
@ApplicationScoped
public class MockLlmValidationGateway implements LlmValidationGateway {

    @Override
    public boolean validate(String provider, String model, String apiKey) throws LlmValidationException {
        return true;
    }
}
