package com.amsha.llm.api.config;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ConnectionValidationRequest;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.ModelEntry;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.MultimodalCapable;
import com.amsha.llm.api.MultimodalRequests;
import com.amsha.llm.api.NetworkPolicyViolation;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.llm.api.ProviderValidationResult;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmContentFilterException;
import com.amsha.llm.api.error.LlmContextOverflowException;
import com.amsha.llm.api.error.LlmExceptionClassifier;
import com.amsha.llm.api.error.LlmExceptionClassifierChain;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmModelExhaustedException;
import com.amsha.llm.api.error.LlmProviderException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;
import com.amsha.llm.api.error.LlmToolCallException;
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
            LlmProviderException.class,
            LlmRateLimitException.class,
            LlmContextOverflowException.class,
            LlmContentFilterException.class,
            LlmAuthenticationException.class,
            LlmServiceUnavailableException.class,
            LlmMalformedResponseException.class,
            LlmToolCallException.class,
            LlmModelExhaustedException.class,
            LlmExceptionClassifierChain.class,
            LlmExceptionClassifier.class,
            AgyOAuthTokenExtractor.class,
            ClaudeCodeOAuthTokenExtractor.class,
            CodexOAuthTokenExtractor.class,
            KimchiOAuthTokenExtractor.class,
            StrictJsonSchemaCapable.class,
            StructuredSchemaRequests.class,
            AudioTranscriptionRequest.class,
            AudioTranscriptionResult.class,
            SpeechSynthesisRequest.class,
            SpeechSynthesisResult.class,
            MultimodalCapability.class,
            MultimodalCapable.class,
            MultimodalCapabilities.class,
            MultimodalRequests.class
        })
public class LlmProviderApiReflectionConfig {}
