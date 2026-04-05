package com.arcclaw.onboarding;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.llm.LlmValidationException;
import com.arcclaw.core.llm.LlmValidationGateway;

@Mock
@ApplicationScoped
public class MockLlmValidationGateway implements LlmValidationGateway {

    @Override
    public boolean validate(String provider, String model, String apiKey) throws LlmValidationException {
        return true;
    }
}
