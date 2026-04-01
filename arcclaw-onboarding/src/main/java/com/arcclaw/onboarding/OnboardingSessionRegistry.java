package com.arcclaw.onboarding;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.runtime.annotations.RegisterForReflection;

@ApplicationScoped
public class OnboardingSessionRegistry {

    @RegisterForReflection
    public static class OnboardingState {
        public enum Step {
            WELCOME(1), PROVIDER(2), CREDENTIALS(3),
            SYSTEM_PROMPT(4), MCP(5), COMPLETE(6);

            public final int number;
            Step(int n) { this.number = n; }

            public Step next() {
                Step[] vals = values();
                int idx = ordinal() + 1;
                return idx < vals.length ? vals[idx] : COMPLETE;
            }
        }

        public Step currentStep = Step.WELCOME;
        public String selectedProvider;
        public String selectedModel;
        public boolean credentialsValidated = false;

        // Jackson / Frontend Mapping
        @JsonProperty("step")
        public int getStepNumber() { return currentStep.number; }

        @JsonProperty("name")
        public String getStepName() { return currentStep.name(); }

        @JsonProperty("provider")
        public String getSelectedProvider() { return selectedProvider; }
        public void setSelectedProvider(String val) { this.selectedProvider = val; }

        @JsonProperty("model")
        public String getSelectedModel() { return selectedModel; }
        public void setSelectedModel(String val) { this.selectedModel = val; }

        @JsonProperty("credentialsValidated")
        public boolean isCredentialsValidated() { return credentialsValidated; }
        public void setCredentialsValidated(boolean val) { this.credentialsValidated = val; }

        public void advance() {
            this.currentStep = currentStep.next();
        }

        public boolean canProceedTo(Step target) {
            return target.number <= currentStep.number + 1;
        }

        public Step getCurrentStep() { return currentStep; }
        public void setCurrentStep(Step step) { this.currentStep = step; }
    }

    private final Cache<String, OnboardingState> sessions = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(500)
            .build();

    public OnboardingState getOrCreate(String sessionId) {
        return sessions.get(sessionId, k -> new OnboardingState());
    }

    public void remove(String sessionId) {
        sessions.invalidate(sessionId);
    }
}
