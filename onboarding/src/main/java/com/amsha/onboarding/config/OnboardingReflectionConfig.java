package com.amsha.onboarding.config;

import com.amsha.onboarding.dto.CompleteResponse;
import com.amsha.onboarding.dto.McpServerRequest;
import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.onboarding.dto.PluginStepDto;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.dto.SettingsDto;
import com.amsha.onboarding.dto.Step2Request;
import com.amsha.onboarding.dto.Step3Request;
import com.amsha.onboarding.dto.Step3Response;
import com.amsha.onboarding.dto.Step4Request;
import com.amsha.onboarding.dto.Step5Request;
import com.amsha.onboarding.dto.Step5Response;
import com.amsha.onboarding.dto.StepResponse;

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
