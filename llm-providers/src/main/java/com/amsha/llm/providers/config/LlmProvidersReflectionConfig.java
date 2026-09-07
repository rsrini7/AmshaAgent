package com.amsha.llm.providers.config;

import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.audio.OmlxAudioTranscriptionProvider;
import com.amsha.llm.providers.audio.OmlxSpeechSynthesisProvider;
import com.amsha.llm.providers.catalog.StaticProviderCatalogAdapter;
import com.amsha.llm.providers.helper.AgyCliExecutor;
import com.amsha.llm.providers.helper.AgyTokenResolver;
import com.amsha.llm.providers.helper.ClaudeCodeTokenResolver;
import com.amsha.llm.providers.helper.CodexTokenResolver;
import com.amsha.llm.providers.helper.FoundryCliExecutor;
import com.amsha.llm.providers.helper.FoundryDiscoveryHelper;
import com.amsha.llm.providers.helper.KimchiTokenResolver;
import com.amsha.llm.providers.reasoning.AgyProviderFactory;
import com.amsha.llm.providers.reasoning.CerebrasProviderFactory;
import com.amsha.llm.providers.reasoning.ClaudeCodeProviderFactory;
import com.amsha.llm.providers.reasoning.CloudflareProviderFactory;
import com.amsha.llm.providers.reasoning.CodexProviderFactory;
import com.amsha.llm.providers.reasoning.CohereProviderFactory;
import com.amsha.llm.providers.reasoning.FoundryProviderFactory;
import com.amsha.llm.providers.reasoning.GeminiProviderFactory;
import com.amsha.llm.providers.reasoning.GenericOpenAiProviderFactory;
import com.amsha.llm.providers.reasoning.GroqProviderFactory;
import com.amsha.llm.providers.reasoning.HuggingFaceProviderFactory;
import com.amsha.llm.providers.reasoning.KimchiProviderFactory;
import com.amsha.llm.providers.reasoning.LlamaCppProviderFactory;
import com.amsha.llm.providers.reasoning.MistralProviderFactory;
import com.amsha.llm.providers.reasoning.NvidiaProviderFactory;
import com.amsha.llm.providers.reasoning.OllamaProviderFactory;
import com.amsha.llm.providers.reasoning.OmlxProviderFactory;
import com.amsha.llm.providers.reasoning.OpenAiProviderFactory;
import com.amsha.llm.providers.reasoning.OpenCodeProviderFactory;
import com.amsha.llm.providers.reasoning.OpenRouterProviderFactory;
import com.amsha.llm.providers.reasoning.SarvamProviderFactory;
import com.amsha.llm.providers.routing.CerebrasLlmProviderFactory;
import com.amsha.llm.providers.routing.CloudflareLlmProviderFactory;
import com.amsha.llm.providers.routing.CodexLlmProviderFactory;
import com.amsha.llm.providers.routing.CohereLlmProviderFactory;
import com.amsha.llm.providers.routing.FoundryLlmProviderFactory;
import com.amsha.llm.providers.routing.GeminiLlmProviderFactory;
import com.amsha.llm.providers.routing.GenericOpenAiLlmProviderFactory;
import com.amsha.llm.providers.routing.GroqLlmProviderFactory;
import com.amsha.llm.providers.routing.HuggingFaceLlmProviderFactory;
import com.amsha.llm.providers.routing.KimchiLlmProviderFactory;
import com.amsha.llm.providers.routing.LlamaCppLlmProviderFactory;
import com.amsha.llm.providers.routing.MistralLlmProviderFactory;
import com.amsha.llm.providers.routing.NvidiaLlmProviderFactory;
import com.amsha.llm.providers.routing.OllamaLlmProviderFactory;
import com.amsha.llm.providers.routing.OmlxLlmProviderFactory;
import com.amsha.llm.providers.routing.OpenAiLlmProviderFactory;
import com.amsha.llm.providers.routing.OpenCodeLlmProviderFactory;
import com.amsha.llm.providers.routing.OpenRouterLlmProviderFactory;
import com.amsha.llm.providers.routing.SarvamLlmProviderFactory;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClient;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilder;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilderFactory;
import com.amsha.llm.providers.wrapper.AgyResponsesChatModel;
import com.amsha.llm.providers.wrapper.ClaudeCodeResponsesChatModel;
import com.amsha.llm.providers.wrapper.CodexResponsesChatModel;
import com.amsha.llm.providers.wrapper.FoundryResponsesChatModel;
import com.amsha.llm.providers.wrapper.KimchiResponsesChatModel;
import com.amsha.llm.providers.wrapper.NvidiaMessageSanitizer;

import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClient;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilder;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilderFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Central GraalVM native reflection registration for {@code amsha-llm-providers}.
 * Registers reasoning factories, routing factories, custom chat model wrappers,
 * token extractors, CLI executors, and catalog adapter types.
 */
@RegisterForReflection(
        targets = {
            ChatModelFactory.class,
            StaticProviderCatalogAdapter.class,
            AgyCliExecutor.class,
            AgyTokenResolver.class,
            ClaudeCodeTokenResolver.class,
            CodexTokenResolver.class,
            FoundryCliExecutor.class,
            FoundryDiscoveryHelper.class,
            KimchiTokenResolver.class,
            AgyResponsesChatModel.class,
            ClaudeCodeResponsesChatModel.class,
            CodexResponsesChatModel.class,
            FoundryResponsesChatModel.class,
            KimchiResponsesChatModel.class,
            NvidiaMessageSanitizer.class,
            AgyProviderFactory.class,
            OmlxAudioTranscriptionProvider.class,
            OmlxSpeechSynthesisProvider.class,
            CerebrasProviderFactory.class,
            ClaudeCodeProviderFactory.class,
            CloudflareProviderFactory.class,
            CodexProviderFactory.class,
            CohereProviderFactory.class,
            FoundryProviderFactory.class,
            GeminiProviderFactory.class,
            GenericOpenAiProviderFactory.class,
            GroqProviderFactory.class,
            HuggingFaceProviderFactory.class,
            KimchiProviderFactory.class,
            LlamaCppProviderFactory.class,
            MistralProviderFactory.class,
            NvidiaProviderFactory.class,
            OllamaProviderFactory.class,
            OmlxProviderFactory.class,
            OpenAiProviderFactory.class,
            OpenCodeProviderFactory.class,
            OpenRouterProviderFactory.class,
            SarvamProviderFactory.class,
            com.amsha.llm.providers.routing.AgyProviderFactory.class, // fully qualified to disambiguate
            CerebrasLlmProviderFactory.class,
            com.amsha.llm.providers.routing.ClaudeCodeProviderFactory.class, // fully qualified to disambiguate
            CloudflareLlmProviderFactory.class,
            CodexLlmProviderFactory.class,
            CohereLlmProviderFactory.class,
            FoundryLlmProviderFactory.class,
            GeminiLlmProviderFactory.class,
            GenericOpenAiLlmProviderFactory.class,
            GroqLlmProviderFactory.class,
            HuggingFaceLlmProviderFactory.class,
            KimchiLlmProviderFactory.class,
            LlamaCppLlmProviderFactory.class,
            MistralLlmProviderFactory.class,
            NvidiaLlmProviderFactory.class,
            OllamaLlmProviderFactory.class,
            OmlxLlmProviderFactory.class,
            OpenAiLlmProviderFactory.class,
            OpenCodeLlmProviderFactory.class,
            OpenRouterLlmProviderFactory.class,
            SarvamLlmProviderFactory.class,
            JaxRsHttpClientBuilder.class,
            JaxRsHttpClient.class,
            JaxRsHttpClientBuilderFactory.class,
            GovernedLangChainHttpClientBuilder.class,
            GovernedLangChainHttpClient.class,
            GovernedLangChainHttpClientBuilderFactory.class
        },
        classNames = {
            "com.amsha.llm.providers.catalog.StaticProviderCatalogAdapter$RawProvider",
            "com.amsha.llm.providers.audio.OmlxSpeechSynthesisProvider$BoundedAudioSubscriber",
            "com.amsha.llm.providers.audio.OmlxSpeechSynthesisProvider$ResponseTooLargeException",
            "com.amsha.llm.providers.wrapper.AgyResponsesChatModel$SseAccumulator",
            "com.amsha.llm.providers.wrapper.AgyResponsesChatModel$ToolExecutionRequestBuilder",
            "com.amsha.llm.providers.wrapper.ClaudeCodeResponsesChatModel$ClaudeSseAccumulator",
            "com.amsha.llm.providers.wrapper.CodexResponsesChatModel$SseAccumulator",
            "com.amsha.llm.providers.wrapper.FoundryResponsesChatModel$SseAccumulator",
            "com.amsha.llm.providers.wrapper.FoundryResponsesChatModel$ToolCallAccumulator",
            "com.amsha.llm.providers.wrapper.KimchiResponsesChatModel$SseAccumulator",
            "com.amsha.llm.providers.wrapper.KimchiResponsesChatModel$ToolCallBuilder"
        })
public class LlmProvidersReflectionConfig {}
