package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.ChatModelFactory;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Agent-reasoning provider for Cloudflare Workers AI. */
@ApplicationScoped
public class CloudflareProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "amsha.llm.cloudflare.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "amsha.llm.cloudflare.account-id")
    Optional<String> accountId;

    @ConfigProperty(name = "amsha.llm.cloudflare.base-url", defaultValue = ModelDefaults.DEFAULT_CLOUDFLARE_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.cloudflare.model", defaultValue = ModelDefaults.DEFAULT_CLOUDFLARE_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_CLOUDFLARE;
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public String baseUrl() {
        try {
            return resolveEffectiveBaseUrl();
        } catch (Exception _) {
            return baseUrl;
        }
    }

    @Override
    public ChatModel create() {
        return createModel(modelName);
    }

    @Override
    public ChatModel create(String customModelName) {
        return createModel(customModelName);
    }

    public String resolveEffectiveBaseUrl() {
        if (baseUrl != null && baseUrl.contains("{account_id}")) {
            String accId = accountId
                    .filter(id -> !id.isBlank())
                    .or(() -> Optional.ofNullable(System.getProperty("AMSHA_CLOUDFLARE_ACCOUNT_ID"))
                            .filter(id -> !id.isBlank()))
                    .or(() -> Optional.ofNullable(System.getProperty("amsha.llm.cloudflare.account-id"))
                            .filter(id -> !id.isBlank()))
                    .or(() -> Optional.ofNullable(System.getenv("AMSHA_CLOUDFLARE_ACCOUNT_ID"))
                            .filter(id -> !id.isBlank()))
                    .orElseThrow(
                            () -> new IllegalStateException(
                                    "Cloudflare Workers AI requires a non-empty account ID (AMSHA_CLOUDFLARE_ACCOUNT_ID) or a fully resolved AMSHA_CLOUDFLARE_BASE_URL"));
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

    private ChatModel createModel(String model) {
        String key = apiKey.filter(k -> !k.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty("AMSHA_CLOUDFLARE_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty("amsha.llm.cloudflare.api-key"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_CLOUDFLARE_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .orElseThrow(
                        () -> new IllegalStateException("Cloudflare Workers AI requires AMSHA_CLOUDFLARE_API_KEY"));
        String effectiveUrl = resolveEffectiveBaseUrl();
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                model,
                ModelDefaults.PROVIDER_CLOUDFLARE,
                (entryKey, entryModel) -> ChatModelFactory.createOpenAi(
                        effectiveUrl,
                        entryKey,
                        entryModel,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp()));
        if (entries.size() == 1) return entries.getFirst().model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_CLOUDFLARE, entries, 5);
    }
}
