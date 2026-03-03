package com.arcclaw.app.routing;

import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.common.annotation.Identifier;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
public class IntentClassifierServiceTest {

    @Inject
    IntentClassifierService service;

    @InjectMock
    @Identifier("intent-classifier")
    ChatLanguageModel classifierModel;

    @Test
    void testNormalRouting() {
        when(classifierModel.chat(anyString())).thenReturn("saraswati");
        String role = service.classify("tell me about the repo");
        Assertions.assertEquals("researcher", role);
    }

    @Test
    void testDestructiveOverride_Researcher() {
        // 'saraswati' maps to 'researcher' which is in TOOL_CAPABLE_ROLES
        when(classifierModel.chat(anyString())).thenReturn("saraswati");
        
        // "delete" is a destructive keyword
        String role = service.classify("delete the repository");
        
        Assertions.assertEquals("coordinator", role, 
            "Destructive command for researcher must be overridden to coordinator");
    }

    @Test
    void testDestructiveOverride_Coordinator() {
        when(classifierModel.chat(anyString())).thenReturn("coordinator");
        
        String role = service.classify("purge all data");
        
        Assertions.assertEquals("coordinator", role, 
            "Coordinator should stay coordinator even with destructive words");
    }

    @Test
    void testNoOverrideForSafeCommands() {
        when(classifierModel.chat(anyString())).thenReturn("saraswati");
        
        String role = service.classify("list all repositories");
        
        Assertions.assertEquals("researcher", role, 
            "Safe commands should not be overridden");
    }

    @Test
    void testNoOverrideForCoder_BrahmaIsExempt() {
        // 'brahma' maps to 'coder' via sanitize()
        // 'coder' is NOT in TOOL_CAPABLE_ROLES, so destructive override does not apply
        when(classifierModel.chat(anyString())).thenReturn("brahma");
        
        String role = service.classify("delete the old data files");
        
        Assertions.assertEquals("coder", role, 
            "Coder (brahma) is exempt from destructive override — " +
            "it has no tool access so override is not needed");
    }
}
