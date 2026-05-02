package com.arcclaw.deployment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;
import org.junit.jupiter.api.Test;

class AgentFunctionProcessorTest {

    @Test
    @SuppressWarnings("unchecked")
    void testBuildSchemaBasicTypes() throws Exception {
        AgentFunctionProcessor processor = new AgentFunctionProcessor();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        Set<String> processed = new HashSet<>();
        BuildProducer<ReflectiveClassBuildItem> producer = mock(BuildProducer.class);
        IndexView index = mock(IndexView.class);

        // String
        Method buildSchema = AgentFunctionProcessor.class.getDeclaredMethod(
            "buildSchema", IndexView.class, Type.class, ObjectNode.class, Set.class, BuildProducer.class);
        buildSchema.setAccessible(true);

        Type stringType = Type.create(DotName.createSimple("java.lang.String"), Type.Kind.CLASS);
        buildSchema.invoke(processor, index, stringType, node, processed, producer);
        assertEquals("string", node.get("type").asText());

        // Integer
        node = mapper.createObjectNode();
        Type intType = Type.create(DotName.createSimple("int"), Type.Kind.PRIMITIVE);
        buildSchema.invoke(processor, index, intType, node, processed, producer);
        assertEquals("integer", node.get("type").asText());

        // Boolean
        node = mapper.createObjectNode();
        Type boolType = Type.create(DotName.createSimple("boolean"), Type.Kind.PRIMITIVE);
        buildSchema.invoke(processor, index, boolType, node, processed, producer);
        assertEquals("boolean", node.get("type").asText());
    }
}
