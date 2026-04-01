package com.arcclaw.onboarding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.llm.LlmValidationGateway;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultConstants;
import com.arcclaw.mcp.transport.McpClientService;
import com.arcclaw.onboarding.OnboardingSession.Step;
import com.arcclaw.onboarding.dto.CompleteResponse;
import com.arcclaw.onboarding.dto.McpServerRequest;
import com.arcclaw.onboarding.dto.OnboardingStatusDto;
import com.arcclaw.onboarding.dto.PluginStepDto;
import com.arcclaw.onboarding.dto.Step2Request;
import com.arcclaw.onboarding.dto.Step3Request;
import com.arcclaw.onboarding.dto.Step3Response;
import com.arcclaw.onboarding.dto.Step4Request;
import com.arcclaw.onboarding.dto.Step5Request;
import com.arcclaw.onboarding.dto.Step5Response;
import com.arcclaw.onboarding.dto.StepResponse;
import com.arcclaw.onboarding.spi.OnboardingStepProvider;
import io.smallrye.common.annotation.RunOnVirtualThread;
import org.jboss.logging.Logger;

// removed McpConnectResource import


@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class OnboardingResource {

    private static final Logger log = Logger.getLogger(OnboardingResource.class);

    @Inject OnboardingSession session;
    @Inject SecretVault vault;
    @Inject LlmValidationGateway llmValidationGateway;
    @Inject Instance<OnboardingStepProvider> pluginProviders;
    @Inject McpClientService mcpClientService;

    private static final Map<String, String> DEFAULT_MODELS = Map.of(
        "groq",        "llama-3.1-8b-instant",
        "openai",      "gpt-4o-mini",
        "ollama",      "llama3.2",
        "anthropic",   "claude-haiku-4-5",
        "claude-code", "claude-sonnet-4-5"
    );

    @GET @Path("/status")
    public OnboardingStatusDto status() {
        return new OnboardingStatusDto(
            session.getCurrentStep().number,
            session.getCurrentStep().name(),
            session.getSelectedProvider(),
            session.getSelectedModel(),
            session.isCredentialsValidated()
        );
    }

    @POST @Path("/step/1")
    public Response step1() {
        if (!session.canProceedTo(Step.PROVIDER))
            return Response.status(403).build();
        session.advance();
        return Response.ok(new StepResponse(2)).build();
    }

    @POST @Path("/step/2")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response step2(Step2Request req) {
        if (!session.canProceedTo(Step.CREDENTIALS))
            return Response.status(403).build();
        String model = (req.model() != null && !req.model().isBlank())
            ? req.model()
            : DEFAULT_MODELS.getOrDefault(req.provider(), "");
        session.setSelectedProvider(req.provider());
        session.setSelectedModel(model);
        session.advance();
        return Response.ok(new StepResponse(3)).build();
    }

    @POST @Path("/step/3")
    @Consumes(MediaType.APPLICATION_JSON)
    @RunOnVirtualThread
    public Response step3(Step3Request req) {
        if (!session.canProceedTo(Step.SYSTEM_PROMPT))
            return Response.status(403).build();

        String provider = session.getSelectedProvider();

        try {
            boolean valid = llmValidationGateway.validate(
                provider, session.getSelectedModel(), req.apiKey()
            );

            if (valid) {
                session.setCredentialsValidated(true);
                session.advance();
                return Response.ok(new Step3Response(4, true, null)).build();
            } else {
                return Response.ok(new Step3Response(3, false, "Unexpected response from validation")).build();
            }

        } catch (Exception e) {
            return Response.ok(new Step3Response(3, false, e.getMessage())).build();
        }
    }

    @POST @Path("/step/4")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response step4(Step4Request req) {
        if (!session.canProceedTo(Step.MCP))
            return Response.status(403).build();
        String prompt = (req.systemPrompt() == null || req.systemPrompt().isBlank())
            ? "You are ArcClaw, a helpful AI assistant."
            : req.systemPrompt();
        vault.storeSystemKey(VaultConstants.SYSTEM_PROMPT_KEY, prompt.getBytes(StandardCharsets.UTF_8));
        session.advance();
        return Response.ok(new StepResponse(5)).build();
    }

    @POST @Path("/step/5")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response step5(Step5Request req) {
        if (!session.canProceedTo(Step.COMPLETE))
            return Response.status(403).build();

        List<String> registered = new ArrayList<>();

        if (req.mcpServers() != null) {
            for (McpServerRequest s : req.mcpServers()) {
                try {
                    if ("stdio".equals(s.transport())) {
                        List<String> argList = (s.args() != null && !s.args().isBlank()) 
                            ? Arrays.asList(s.args().split("\\s+")) : List.of();
                        Map<String, String> envMap = new HashMap<>();
                        if (s.env() != null && !s.env().isBlank()) {
                            for (String line : s.env().split("\\n")) {
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
                    log.warnf("MCP registration failed for %s: %s", s.name(), e.getMessage());
                }
            }
        }

        session.advance();
        return Response.ok(new Step5Response(6, registered)).build();
    }

    @GET @Path("/complete")
    public CompleteResponse complete() {
        List<PluginStepDto> plugins = StreamSupport
            .stream(pluginProviders.spliterator(), false)
            .sorted(Comparator.comparingInt(OnboardingStepProvider::order))
            .map(p -> new PluginStepDto(
                p.stepId(), p.title(), p.isConfigured()))
            .toList();

        return new CompleteResponse(
            true, "/#/chat",
            session.getSelectedProvider(),
            session.getSelectedModel(),
            plugins
        );
    }
}
