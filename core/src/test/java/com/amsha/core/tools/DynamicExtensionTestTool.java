package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.security.api.ToolOperation;

@ApplicationScoped
@ToolMetadata(
        capabilityId = "DYNAMIC_TEST",
        description = "A dummy tool for dynamic extension testing",
        defaultRoles = {"researcher", "coordinator"})
public class DynamicExtensionTestTool implements AgentTool {

    @Override
    public String name() {
        return "dynamic_test_tool";
    }

    @Override
    public String description() {
        return "Dummy tool";
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public ToolOperation requiredOperation() {
        return ToolOperation.READ_FS;
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        return ToolResponse.success("dynamic success");
    }
}
