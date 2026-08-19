package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeGenerationPolicyTest {

    private final CodeGenerationPolicy policy = new CodeGenerationPolicy();

    @Test
    void researcherJsonDataBlockIsNotTreatedAsCodeGeneration() {
        String answer = """
                ```json
                {
                  "spring_boot_lts_version": "4.0.5",
                  "quarkus_lts_version": "3.33"
                }
                ```
                """;

        assertFalse(policy.isGeneratedCodeBlock(answer, "researcher"));
    }

    @Test
    void researcherJavaCodeBlockIsStillTreatedAsCodeGeneration() {
        String answer = """
                ```java
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                ```
                """;

        assertTrue(policy.isGeneratedCodeBlock(answer, "researcher"));
    }

    @Test
    void researcherStructuredDataWithClassOrMethodKeysIsNotTreatedAsCode() {
        String answer = """
                ```json
                {
                  "service": "OrderService",
                  "class": "com.example.OrderHandler",
                  "method": "POST",
                  "function": "processPayment"
                }
                ```
                """;

        assertFalse(policy.isGeneratedCodeBlock(answer, "researcher"));
    }
}
