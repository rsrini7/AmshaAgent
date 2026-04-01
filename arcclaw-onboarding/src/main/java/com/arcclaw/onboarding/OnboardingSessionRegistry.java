package com.arcclaw.onboarding;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@ApplicationScoped
public class OnboardingSessionRegistry {

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

        public volatile Step currentStep = Step.WELCOME;
        public volatile String selectedProvider;
        public volatile String selectedModel;
        public volatile boolean credentialsValidated = false;

        public boolean canProceedTo(Step target) {
            return target.number <= currentStep.number + 1;
        }

        public void advance() {
            this.currentStep = currentStep.next();
        }
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
