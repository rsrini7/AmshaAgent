package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Cloudflare Workers AI.
 *
 * <p>Cloudflare accepts standard OpenAI chat completion wire format with bearer authentication.
 */
@ApplicationScoped
@Unremovable
public class CloudflareLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_CLOUDFLARE;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String effectiveBaseUrl = resolveEffectiveBaseUrl(config.baseUrl());
        String apiKey = config.apiKey()
                .filter(k -> !k.isBlank())
                .or(() -> ConfigProvider.getConfig()
                        .getOptionalValue(LlmConfigKeys.CLOUDFLARE_API_KEY, String.class)
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty(ConfigKeys.CLOUDFLARE_API_KEY))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty(LlmConfigKeys.CLOUDFLARE_API_KEY))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv(ConfigKeys.CLOUDFLARE_API_KEY))
                        .filter(k -> !k.isBlank()))
                .orElseThrow(() -> new IllegalStateException(
                        "Cloudflare provider requires an API key (AMSHA_CLOUDFLARE_API_KEY)"));

        var builder = ChatModelFactory.openAiBuilder(
                effectiveBaseUrl,
                apiKey,
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofSeconds(30)),
                config.maxRetries().orElse(3),
                0.0,
                false,
                false);

        ChatModelFactory.applyJsonResponseFormat(
                builder, config.structuredOutputMode(), config.toolless(), effectiveBaseUrl);

        return builder.build();
    }

    private String resolveEffectiveBaseUrl(String baseUrl) {
        if (baseUrl != null && baseUrl.contains("{account_id}")) {
            var cfg = ConfigProvider.getConfig();
            String accId = cfg.getOptionalValue(LlmConfigKeys.CLOUDFLARE_ACCOUNT_ID, String.class)
                    .filter(id -> !id.isBlank())
                    .or(() -> Optional.ofNullable(System.getProperty(ConfigKeys.CLOUDFLARE_ACCOUNT_ID))
                            .filter(id -> !id.isBlank()))
                    .or(() -> Optional.ofNullable(System.getProperty(LlmConfigKeys.CLOUDFLARE_ACCOUNT_ID))
                            .filter(id -> !id.isBlank()))
                    .or(() -> Optional.ofNullable(System.getenv(ConfigKeys.CLOUDFLARE_ACCOUNT_ID))
                            .filter(id -> !id.isBlank()))
                    .orElseThrow(
                            () -> new IllegalStateException(
                                    "Cloudflare Workers AI requires a non-empty account ID (AMSHA_CLOUDFLARE_ACCOUNT_ID) or a fully resolved base URL"));
            String cleanAcc = sanitizeAccountId(accId);
            String resolved = baseUrl.replace("{account_id}", cleanAcc);
            resolved = normalizeBaseUrl(resolved);
            if (resolved.contains("{account_id}") || resolved.contains("//ai/v1")) {
                throw new IllegalStateException(
                        "Cloudflare Workers AI base URL failed validation after account ID substitution: " + resolved);
            }
            return resolved;
        }
        return normalizeBaseUrl(baseUrl);
    }

    private static String sanitizeAccountId(String rawAccId) {
        if (rawAccId == null || rawAccId.isBlank()) return "";
        String clean = rawAccId.trim();
        if (clean.contains("accounts/")) {
            clean = clean.substring(clean.indexOf("accounts/") + "accounts/".length());
        }
        if (clean.contains("/")) {
            clean = clean.substring(0, clean.indexOf("/"));
        }
        return clean.trim();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String normalized = url.trim();
        // Defense for MP Config "${ENV:…/{account_id}/ai/v1}" early-'}' corruption that
        // appends a literal "/ai/v1}" (and may duplicate "/ai/v1" before normalize).
        while (normalized.endsWith("}")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        while (normalized.contains("/ai/v1/ai/v1")) {
            normalized = normalized.replace("/ai/v1/ai/v1", "/ai/v1");
        }
        while (normalized.contains("/v1/v1")) {
            normalized = normalized.replace("/v1/v1", "/v1");
        }
        if (normalized.indexOf('}') >= 0) {
            throw new IllegalStateException("Cloudflare Workers AI base URL contains illegal '}': " + normalized);
        }
        return normalized;
    }
}
