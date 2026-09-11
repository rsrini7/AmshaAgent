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

    @Test
    void coordinatorXmlMavenCoordinatesBlockIsNotTreatedAsCodeGeneration() {
        String answer = """
                Here are the latest LTS versions and coordinates:

                ```xml
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>io.quarkus.platform</groupId>
                            <artifactId>quarkus-bom</artifactId>
                            <version>3.15.3</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                ```
                """;

        assertFalse(policy.isGeneratedCodeBlock(answer, "coordinator"));
    }

    @Test
    void coordinatorUntaggedXmlBlockIsNotTreatedAsCodeGeneration() {
        String answer = """
                ```
                <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-web</artifactId>
                    <version>3.4.3</version>
                </dependency>
                ```
                """;

        assertFalse(policy.isGeneratedCodeBlock(answer, "coordinator"));
    }

    @Test
    void researcherJavaCodeInsideXmlFenceIsStillTreatedAsCodeGeneration() {
        String answer = """
                ```xml
                public class FakeXml {
                    public static void main(String[] args) {}
                }
                ```
                """;

        assertTrue(policy.isGeneratedCodeBlock(answer, "researcher"));
    }
}
