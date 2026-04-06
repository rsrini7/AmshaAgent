package com.arcclaw.onboarding;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.llm.LlmValidationGateway;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultConstants;
import com.arcclaw.core.security.VaultKey;
import com.arcclaw.onboarding.dto.PromptUpdateRequest;
import com.arcclaw.onboarding.dto.ProviderUpdateRequest;
import com.arcclaw.onboarding.dto.SettingsDto;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/settings")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SettingsResource {

    @Inject SecretVault vault;
    @Inject LlmValidationGateway llmValidationGateway;
    @Inject OnboardingSessionRegistry registry;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @ConfigProperty(name = "arcclaw.api.keys.internal", defaultValue = "dev-key")
    String internalApiKey;

    private void checkAuth(String key) {
        if (key == null || !key.equals(internalApiKey)) {
            throw new WebApplicationException(
                Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Unauthorized: Invalid or missing API Key"))
                    .build());
        }
    }

    @GET
    public SettingsDto getSettings(@HeaderParam("X-ArcClaw-API-Key") String key) {
        checkAuth(key);
        String provider = readVaultString(VaultConstants.LLM_PROVIDER_KEY, "");
        String model    = readVaultString(VaultConstants.LLM_MODEL_KEY, "");
        String prompt   = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
        boolean hasKey  = vault.systemKeyExists(VaultConstants.LLM_API_KEY);

        String displayPrompt = prompt.length() > 200
            ? prompt.substring(0, 199) + "…"
            : prompt;

        return new SettingsDto(provider, model, displayPrompt, hasKey, appVersion);
    }

    @GET
    @Path("/prompt")
    public Response getPrompt(@HeaderParam("X-ArcClaw-API-Key") String key) {
        checkAuth(key);
        String prompt = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
        return Response.ok(Map.of("systemPrompt", prompt)).build();
    }

    @PATCH
    @Path("/provider")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response updateProvider(@HeaderParam("X-ArcClaw-API-Key") String key, ProviderUpdateRequest req) {
        checkAuth(key);
        if (req.provider() == null || req.provider().isBlank()) {
            return Response.status(400)
                .entity(Map.of("error", "provider is required")).build();
        }

        boolean isClaudeCode = "claude-code".equals(req.provider());
        boolean isOllama     = "ollama".equals(req.provider());

        if (!isClaudeCode && !isOllama) {
            if (req.apiKey() == null || req.apiKey().isBlank()) {
                return Response.status(400)
                    .entity(Map.of("error", "apiKey is required for " + req.provider())).build();
            }
            try {
                boolean valid = llmValidationGateway.validate(req.provider(), req.model(), req.apiKey());
                if (!valid) {
                    return Response.status(422)
                        .entity(Map.of("error", "API key validation failed for " + req.provider()))
                        .build();
                }
            } catch (Exception e) {
                return Response.status(422)
                    .entity(Map.of("error", e.getMessage())).build();
            }
        }

        vault.storeSystemKey(VaultConstants.LLM_PROVIDER_KEY, req.provider().getBytes(StandardCharsets.UTF_8));
        vault.storeSystemKey(VaultConstants.LLM_MODEL_KEY, (req.model() != null ? req.model() : "").getBytes(StandardCharsets.UTF_8));
        if (!isClaudeCode && req.apiKey() != null) {
            vault.storeSystemKey(VaultConstants.LLM_API_KEY, req.apiKey().getBytes(StandardCharsets.UTF_8));
        }

        return Response.ok(Map.of("updated", true)).build();
    }

    @PATCH
    @Path("/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updatePrompt(@HeaderParam("X-ArcClaw-API-Key") String key, PromptUpdateRequest req) {
        checkAuth(key);
        if (req.systemPrompt() == null || req.systemPrompt().isBlank()) {
            return Response.status(400)
                .entity(Map.of("error", "systemPrompt is required")).build();
        }
        vault.storeSystemKey(VaultConstants.SYSTEM_PROMPT_KEY, req.systemPrompt().getBytes(StandardCharsets.UTF_8));
        return Response.ok(Map.of("updated", true)).build();
    }

    @POST
    @Path("/reset")
    public Response reset(@HeaderParam("X-ArcClaw-API-Key") String key) {
        checkAuth(key);
        registry.invalidateAll();
        
        // Clear all LLM related keys to force re-onboarding (§reset-fix)
        vault.removeSystemKey(VaultConstants.LLM_PROVIDER_KEY);
        vault.removeSystemKey(VaultConstants.LLM_MODEL_KEY);
        vault.removeSystemKey(VaultConstants.LLM_API_KEY);
        vault.removeSystemKey(VaultConstants.SYSTEM_PROMPT_KEY);
        
        return Response.ok(Map.of("reset", true,
            "message", "Onboarding sessions cleared. Cache cleared. Refresh to re-run the wizard.")).build();
    }

    private String readVaultString(String key, String fallback) {
      return vault.retrieveSystemKey(key)
          .map(vk -> {
            try (VaultKey k = vk) {
              return new String(k.get(), StandardCharsets.UTF_8);
            } catch (Exception e) {
              return fallback;
            }
          })
          .orElse(fallback);
    }
}
