package com.arcclaw.onboarding;

import jakarta.enterprise.context.SessionScoped;
import java.io.Serializable;

@SessionScoped
public class OnboardingSession implements Serializable {

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

        public boolean isAfter(Step other) {
            return this.number > other.number;
        }
    }

    private Step currentStep = Step.WELCOME;
    private String selectedProvider;
    private String selectedModel;
    private boolean credentialsValidated = false;

    public Step getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(Step currentStep) {
        this.currentStep = currentStep;
    }

    public String getSelectedProvider() {
        return selectedProvider;
    }

    public void setSelectedProvider(String selectedProvider) {
        this.selectedProvider = selectedProvider;
    }

    public String getSelectedModel() {
        return selectedModel;
    }

    public void setSelectedModel(String selectedModel) {
        this.selectedModel = selectedModel;
    }

    public boolean isCredentialsValidated() {
        return credentialsValidated;
    }

    public void setCredentialsValidated(boolean credentialsValidated) {
        this.credentialsValidated = credentialsValidated;
    }

    /**
     * Returns true only if target.number <= currentStep.number + 1.
     * Prevents skipping steps via direct HTTP calls.
     */
    public boolean canProceedTo(Step target) {
        return target.number <= currentStep.number + 1;
    }

    public void advance() {
        this.currentStep = currentStep.next();
    }
}
