package com.arcclaw.onboarding.config;

import com.arcclaw.onboarding.dto.CompleteResponse;
import com.arcclaw.onboarding.dto.McpServerRequest;
import com.arcclaw.onboarding.dto.OnboardingStatusDto;
import com.arcclaw.onboarding.dto.PluginStepDto;
import com.arcclaw.onboarding.dto.PromptUpdateRequest;
import com.arcclaw.onboarding.dto.ProviderUpdateRequest;
import com.arcclaw.onboarding.dto.SettingsDto;
import com.arcclaw.onboarding.dto.Step2Request;
import com.arcclaw.onboarding.dto.Step3Request;
import com.arcclaw.onboarding.dto.Step3Response;
import com.arcclaw.onboarding.dto.Step4Request;
import com.arcclaw.onboarding.dto.Step5Request;
import com.arcclaw.onboarding.dto.Step5Response;
import com.arcclaw.onboarding.dto.StepResponse;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Dedicated configuration class to register all Onboarding module DTOs
 * for GraalVM Native Image reflection.
 */
@RegisterForReflection(
        targets = {
            CompleteResponse.class,
            McpServerRequest.class,
            OnboardingStatusDto.class,
            PluginStepDto.class,
            PromptUpdateRequest.class,
            ProviderUpdateRequest.class,
            SettingsDto.class,
            Step2Request.class,
            Step3Request.class,
            Step3Response.class,
            Step4Request.class,
            Step5Request.class,
            Step5Response.class,
            StepResponse.class
        })
public class OnboardingReflectionConfig {
    // Container for @RegisterForReflection
}
