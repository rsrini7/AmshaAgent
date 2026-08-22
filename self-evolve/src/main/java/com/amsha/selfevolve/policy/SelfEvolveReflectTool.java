package com.amsha.selfevolve.policy;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.core.tools.ToolResponse;
import com.amsha.security.api.ToolOperation;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Agent tool that triggers self-evolution directive synthesis on-demand.
 * <p>
 * Analyzes extracted memory patterns, synthesizes behavioral directives via LLM,
 * and creates them in SHADOW mode for safe evaluation.
 * Also performs lifecycle reconciliation on existing SHADOW directives.
 */
@Unremovable
@ApplicationScoped
@ToolMetadata(
        capabilityId = "self_evolve_reflect",
        description = "Trigger self-evolution directive synthesis. Analyzes memory patterns and creates "
                + "behavioral directives in SHADOW mode for safe evaluation. Also promotes/disables "
                + "existing directives based on evaluation history.",
        defaultRoles = {"coordinator"})
public class SelfEvolveReflectTool implements AgentTool {

    private static final Logger log = Logger.getLogger(SelfEvolveReflectTool.class);

    @Inject
    InteractionPolicySynthesizer synthesizer;

    @Inject
    ShadowLifecycleManager lifecycleManager;

    @Inject
    DirectiveStorePort directiveStore;

    @Override
    public String name() {
        return ToolCapability.SELF_EVOLVE_REFLECT.toolName();
    }

    @Override
    public String description() {
        return "Trigger a single self-evolution directive action: synthesize, reconcile, or status.";
    }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty(
                        "action",
                        "Action to perform. Use exactly one of: 'synthesize' (create new directives), "
                                + "'reconcile' (promote/disable existing), or 'status' (view current directives). "
                                + "Default: 'synthesize'. Never batch multiple actions.")
                .build();
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.WRITE_MEMORY;
    }

    @Override
    public IdempotencyMode idempotencyMode() {
        return IdempotencyMode.NON_IDEMPOTENT;
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String userId = WorkspaceContext.getUserIdOrUnknown();
        String action = (String) params.getOrDefault("action", "synthesize");

        return switch (action.toLowerCase()) {
            case "synthesize" -> doSynthesize(userId);
            case "reconcile" -> doReconcile(userId);
            case "status" -> doStatus(userId);
            default ->
                ToolResponse.failure(
                        400, "Unknown action: '" + action + "'. Use 'synthesize', 'reconcile', or 'status'.");
        };
    }

    private ToolResponse doSynthesize(String userId) {
        try {
            List<SynthesizedDirective> newDirectives = synthesizer.synthesize(userId);
            if (newDirectives.isEmpty()) {
                return ToolResponse.success("No new directives synthesized. "
                        + "This may indicate insufficient extracted memory patterns.");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Synthesized ").append(newDirectives.size()).append(" directive(s) in SHADOW mode:\n\n");
            for (SynthesizedDirective d : newDirectives) {
                sb.append("- [")
                        .append(d.state())
                        .append("] ")
                        .append(d.text(), 0, Math.min(100, d.text().length()))
                        .append(" (confidence: ")
                        .append(String.format("%.1f", d.confidence()))
                        .append(")\n");
            }
            sb.append("\nDirectives will be evaluated in future sessions. ");
            sb.append("Promotion to ACTIVE requires 3+ positive evaluations.");
            return ToolResponse.success(sb.toString());
        } catch (Exception e) {
            log.errorf("[ReflectTool] Synthesis failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Synthesis failed: " + e.getMessage());
        }
    }

    private ToolResponse doReconcile(String userId) {
        try {
            int actions = lifecycleManager.reconcileAll();
            return ToolResponse.success("Reconciliation complete. " + actions + " directive(s) promoted or disabled.");
        } catch (Exception e) {
            log.errorf("[ReflectTool] Reconciliation failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Reconciliation failed: " + e.getMessage());
        }
    }

    private ToolResponse doStatus(String userId) {
        try {
            List<SynthesizedDirective> all = directiveStore.getAll(userId, 20);
            if (all.isEmpty()) {
                return ToolResponse.success("No directives exist for this user.");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Directives for user ").append(userId).append(":\n\n");
            for (DirectiveState state : DirectiveState.values()) {
                List<SynthesizedDirective> inState =
                        all.stream().filter(d -> d.state() == state).toList();
                if (!inState.isEmpty()) {
                    sb.append("[")
                            .append(state)
                            .append("] (")
                            .append(inState.size())
                            .append(")\n");
                    for (SynthesizedDirective d : inState) {
                        sb.append("  - ")
                                .append(d.text(), 0, Math.min(80, d.text().length()))
                                .append(" conf=")
                                .append(String.format("%.1f", d.confidence()))
                                .append(" hits=")
                                .append(d.evaluationHits())
                                .append(" misses=")
                                .append(d.evaluationMisses())
                                .append("\n");
                    }
                    sb.append("\n");
                }
            }
            return ToolResponse.success(sb.toString());
        } catch (Exception e) {
            log.errorf("[ReflectTool] Status failed: %s", e.getMessage());
            return ToolResponse.failure(1, "Status query failed: " + e.getMessage());
        }
    }
}
