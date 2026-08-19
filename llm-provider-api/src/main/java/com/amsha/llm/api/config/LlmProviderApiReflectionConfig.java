package com.amsha.llm.api.config;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ConnectionValidationRequest;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.ModelEntry;
import com.amsha.llm.api.NetworkPolicyViolation;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.llm.api.ProviderValidationResult;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.oauth.AgyOAuthTokenExtractor;
import com.amsha.llm.api.oauth.ClaudeCodeOAuthTokenExtractor;
import com.amsha.llm.api.oauth.CodexOAuthTokenExtractor;
import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-llm-provider-api.
 */
@RegisterForReflection(
        targets = {
            AgentLlmConfig.class,
            LlmModelConfig.class,
            StructuredOutputMode.class,
            ModelDefaults.class,
            LlmProviderDescriptor.class,
            ModelEntry.class,
            ConnectionValidationRequest.class,
            ProviderValidationResult.class,
            NetworkProfilePolicy.class,
            NetworkProfilePolicy.EgressMode.class,
            NetworkProfilePolicy.TlsTrustMode.class,
            NetworkPolicyViolation.class,
            AgyOAuthTokenExtractor.class,
            ClaudeCodeOAuthTokenExtractor.class,
            CodexOAuthTokenExtractor.class,
            KimchiOAuthTokenExtractor.class
        })
public class LlmProviderApiReflectionConfig {}
