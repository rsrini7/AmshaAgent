package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import com.amsha.common.util.LogUtil;
import com.amsha.core.api.AgentResponseMapper;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.llm.LlmValidationGateway;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.util.HttpConstants;
import com.amsha.mcp.transport.McpClientService;
import com.amsha.onboarding.OnboardingSessionRegistry.OnboardingState;
import com.amsha.onboarding.OnboardingSessionRegistry.OnboardingState.Step;
import com.amsha.onboarding.dto.CompleteResponse;
import com.amsha.onboarding.dto.McpServerRequest;
import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.onboarding.dto.PluginStepDto;
import com.amsha.onboarding.dto.Step2Request;
import com.amsha.onboarding.dto.Step3Request;
import com.amsha.onboarding.dto.Step3Response;
import com.amsha.onboarding.dto.Step4Request;
import com.amsha.onboarding.dto.Step5Request;
import com.amsha.onboarding.dto.Step5Response;
import com.amsha.onboarding.dto.StepResponse;
import com.amsha.onboarding.spi.OnboardingStepProvider;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class OnboardingResource {

    private static final Logger log = Logger.getLogger(OnboardingResource.class);
    private static final String COOKIE_NAME = "AMSHA_ONBOARDING_SID";

    @Inject
    OnboardingSessionRegistry registry;

    @Inject
    SecretVault vault;

    @Inject
    LlmValidationGateway llmValidationGateway;

    @Inject
    @Any
    Instance<OnboardingStepProvider> pluginProviders;

    @Inject
    McpClientService mcpClientService;

    @Inject
    AuditService auditService;

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String configuredProvider;

    @ConfigProperty(name = "amsha.llm.vault-priority", defaultValue = "false")
    boolean vaultPriority;

    @ConfigProperty(name = "amsha.ui.skip-onboarding", defaultValue = "false")
    boolean skipOnboarding;

    @ConfigProperty(name = "amsha.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalApiKey;

    private static final Map<String, String> DEFAULT_MODELS = Map.ofEntries(
            Map.entry("groq", "meta-llama/llama-4-scout-17b-16e-instruct"),
            Map.entry("openai", "gpt-4o-mini"),
            Map.entry("ollama", "mistral-small:latest"),
            Map.entry("anthropic", "claude-haiku-4-5"),
            Map.entry("claude-code", "claude-sonnet-4-5"),
            Map.entry("openrouter", "openai/gpt-oss-20b:free"),
            Map.entry("nvidia", "bytedance/seed-oss-36b-instruct"),
            Map.entry("cerebras", "llama-3.3-70b"),
            Map.entry("gemini", "gemini-2.0-flash"),
            Map.entry("omlx", "mlx-community/gemma-4-26b-a4b-it-4bit"),
            Map.entry("llamacpp", "qwen3"),
            Map.entry("generic-openai", ""));

    @GET
    @Path("/status")
    @Operation(summary = "Get current onboarding session status and step")
    public Response status(@CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        if (state.currentStep == OnboardingState.Step.WELCOME && (skipOnboarding || backendLlmConfigured())) {
            state.currentStep = OnboardingState.Step.COMPLETE;
            state.credentialsValidated = true;
            if (state.selectedProvider == null || state.selectedProvider.isBlank()) {
                state.selectedProvider = configuredProvider;
            }
        }

        log.infof("status: sid=%s, currentStep=%s", sid, state.currentStep);

        OnboardingStatusDto dto = new OnboardingStatusDto(
                state.currentStep.number,
                state.currentStep.name(),
                state.selectedProvider,
                state.selectedModel,
                state.credentialsValidated);

        Response.ResponseBuilder rb = Response.ok(dto).cookie(createCookie(sid));
        if (state.currentStep == OnboardingState.Step.COMPLETE) {
            rb.cookie(createApiCookie(internalApiKey));
        }
        return rb.build();
    }

    private boolean backendLlmConfigured() {
        if (skipOnboarding) {
            return true;
        }

        if (vault.systemKeyExists(VaultConstants.LLM_API_KEY)) {
            return true;
        }

        String provider = configuredProvider != null ? configuredProvider.trim() : "";
        if (provider.isBlank()) {
            return false;
        }

        if (provider.equalsIgnoreCase("ollama")
                || provider.equalsIgnoreCase("claude-code")
                || provider.equalsIgnoreCase("omlx")
                || provider.equalsIgnoreCase("llamacpp")) {
            return true;
        }

        String apiKey = System.getProperty("AMSHA_LLM_API_KEY", System.getenv("AMSHA_LLM_API_KEY"));
        if (apiKey != null && !apiKey.isBlank()) {
            return true;
        }

        if (!vaultPriority && provider.equalsIgnoreCase("infinite-loop")) {
            String providers = System.getProperty(
                    "AMSHA_INFINITE_LOOP_LLM_PROVIDERS", System.getenv("AMSHA_INFINITE_LOOP_LLM_PROVIDERS"));
            return providers != null && !providers.isBlank();
        }

        return false;
    }

    @POST
    @Path("/step/1")
    @Operation(summary = "Start onboarding and move to Provider selection")
    @SuppressWarnings("java:S1192")
    public Response step1(@CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        log.infof("step1: sid=%s, currentStep=%s", sid, state.currentStep);

        if (!state.canProceedTo(OnboardingSessionRegistry.OnboardingState.Step.PROVIDER)) {
            log.warnf("Forbidden jump: sid=%s tries to reach PROVIDER while at %s", sid, state.currentStep);
            return Response.status(403)
                    .entity(new AmshaErrorResponse("Forbidden", "Onboarding step out of sequence"))
                    .cookie(createCookie(sid))
                    .build();
        }

        state.advance();
        return Response.ok(new StepResponse(2)).cookie(createCookie(sid)).build();
    }

    @POST
    @Path("/step/2")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Select LLM provider and model")
    public Response step2(Step2Request req, @CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        log.infof("step2: sid=%s, currentStep=%s", sid, state.currentStep);

        if (!state.canProceedTo(OnboardingSessionRegistry.OnboardingState.Step.CREDENTIALS)) {
            log.warnf("Forbidden jump: sid=%s tries to reach CREDENTIALS while at %s", sid, state.currentStep);
            return Response.status(403)
                    .entity(new AgentResponseMapper.AgentErrorResponse("Forbidden", "Onboarding step out of sequence"))
                    .cookie(createCookie(sid))
                    .build();
        }

        String model = (req.model() != null && !req.model().isBlank())
                ? req.model()
                : DEFAULT_MODELS.getOrDefault(req.provider(), "");

        state.selectedProvider = req.provider();
        state.selectedModel = model;
        state.advance();

        return Response.ok(new StepResponse(3)).cookie(createCookie(sid)).build();
    }

    @POST
    @Path("/step/3")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate credentials and move to System Prompt step")
    public Response step3(Step3Request req, @CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        if (!state.canProceedTo(OnboardingSessionRegistry.OnboardingState.Step.SYSTEM_PROMPT)) {
            log.warnf("Forbidden jump: sid=%s tries to reach SYSTEM_PROMPT while at %s", sid, state.currentStep);
            return Response.status(403)
                    .entity(new AmshaErrorResponse("Forbidden", "Onboarding step out of sequence"))
                    .cookie(createCookie(sid))
                    .build();
        }

        String provider = state.selectedProvider;

        try {
            boolean valid = llmValidationGateway.validate(provider, state.selectedModel, req.apiKey());

            log.infof("step3 validation: sid=%s, provider=%s, valid=%b", sid, provider, valid);

            if (!valid) {
                return Response.ok(new Step3Response(3, false, "Invalid API key or model combination"))
                        .cookie(createCookie(sid))
                        .build();
            }

            state.credentialsValidated = true;
            state.advance();

            vault.storeSystemKey(VaultConstants.LLM_API_KEY, req.apiKey().getBytes(StandardCharsets.UTF_8));
            vault.storeSystemKey(VaultConstants.LLM_PROVIDER_KEY, provider.getBytes(StandardCharsets.UTF_8));

            auditService.recordMandatory(
                    "onboarding-" + sid,
                    "onboarding-user",
                    "system",
                    SecurityEventType.ONBOARDING_COMPLETED,
                    "step=3 provider=" + provider + " model=" + state.selectedModel);

            return Response.ok(new Step3Response(4, true, null))
                    .cookie(createCookie(sid))
                    .build();

        } catch (Exception e) {
            log.errorf("Validation error for sid=%s: %s", sid, LogUtil.rootMessage(e));
            return Response.status(400)
                    .entity(new Step3Response(3, false, e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/step/4")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Configure default system prompt and move to MCP step")
    public Response step4(Step4Request req, @CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        if (!state.canProceedTo(Step.MCP))
            return Response.status(403)
                    .entity(new AmshaErrorResponse("Forbidden", "Onboarding step out of sequence"))
                    .cookie(createCookie(sid))
                    .build();

        String prompt = (req.systemPrompt() == null || req.systemPrompt().isBlank())
                ? "You are Amsha Agent, a helpful AI assistant."
                : req.systemPrompt();

        vault.storeSystemKey(VaultConstants.SYSTEM_PROMPT_KEY, prompt.getBytes(StandardCharsets.UTF_8));
        state.advance();

        return Response.ok(new StepResponse(5)).cookie(createCookie(sid)).build();
    }

    @POST
    @Path("/step/5")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Register MCP servers and complete onboarding")
    public Response step5(Step5Request req, @CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        if (!state.canProceedTo(Step.COMPLETE))
            return Response.status(403)
                    .entity(new AmshaErrorResponse("Forbidden", "Onboarding step out of sequence"))
                    .cookie(createCookie(sid))
                    .build();

        List<String> registered = new ArrayList<>();

        if (req.mcpServers() != null) {
            for (McpServerRequest s : req.mcpServers()) {
                try {
                    if ("stdio".equals(s.transport())) {
                        List<String> argList = (s.args() != null && !s.args().isBlank())
                                ? Arrays.asList(s.args().split("\\s+"))
                                : List.of();
                        Map<String, String> envMap = new HashMap<>();
                        if (s.env() != null && !s.env().isBlank()) {
                            for (String line : s.env().split("\\n", -1)) {
                                String[] parts = line.split("=", 2);
                                if (parts.length == 2) envMap.put(parts[0].trim(), parts[1].trim());
                            }
                        }
                        mcpClientService.connectStdio(s.name(), s.command(), argList, envMap);
                    } else {
                        mcpClientService.connect(s.name(), s.url());
                    }
                    registered.add(s.name());
                } catch (Exception e) {
                    log.warnf("MCP registration failed for %s: %s", s.name(), LogUtil.rootMessage(e));
                }
            }
        }

        state.advance();

        if (!registered.isEmpty()) {
            auditService.recordMandatory(
                    "onboarding-" + sid,
                    "onboarding-user",
                    "system",
                    SecurityEventType.MCP_SERVER_CONNECTED,
                    "via=onboarding servers=" + String.join(",", registered));
        }

        return Response.ok(new Step5Response(6, registered))
                .cookie(createCookie(sid))
                .build();
    }

    @GET
    @Path("/complete")
    @Operation(summary = "Finalize setup and retrieve API keys for the UI")
    public Response complete(@CookieParam(COOKIE_NAME) Cookie cookie, @Context HttpHeaders headers) {
        String sid = resolveSessionId(cookie, headers);
        OnboardingState state = registry.getOrCreate(sid);

        List<PluginStepDto> plugins = StreamSupport.stream(pluginProviders.spliterator(), false)
                .sorted(Comparator.comparingInt(OnboardingStepProvider::order))
                .map(p -> new PluginStepDto(p.stepId(), p.title(), p.isConfigured()))
                .toList();

        auditService.recordMandatory(
                "onboarding-" + sid,
                "onboarding-user",
                "system",
                SecurityEventType.ONBOARDING_COMPLETED,
                "step=complete provider=" + state.selectedProvider + " model=" + state.selectedModel);

        CompleteResponse dto = new CompleteResponse(
                true, "/#/chat", state.selectedProvider, state.selectedModel, internalApiKey, plugins);

        return Response.ok(dto)
                .cookie(createCookie(sid))
                .cookie(createApiCookie(internalApiKey))
                .build();
    }

    private String resolveSessionId(Cookie cookie, HttpHeaders headers) {
        if (cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }
        if (headers != null) {
            Map<String, Cookie> cookies = headers.getCookies();
            if (cookies != null && cookies.containsKey(COOKIE_NAME)) {
                Cookie c = cookies.get(COOKIE_NAME);
                if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return UUID.randomUUID().toString();
    }

    private NewCookie createCookie(String sid) {
        return new NewCookie.Builder(COOKIE_NAME)
                .value(sid)
                .path("/")
                .maxAge(3600)
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    private NewCookie createApiCookie(String key) {
        return new NewCookie.Builder(HttpConstants.COOKIE_AMSHA_API_KEY)
                .value(key)
                .path("/")
                .maxAge(3600 * 24 * 30) // 30 days
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }
}
