package com.arcclaw.deployment;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.RecordComponentInfo;
import org.jboss.jandex.Type;

import com.arcclaw.channel.api.structured.AgentFunction;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;

/**
 * Processes @AgentFunction interfaces at build time.
 * Generates JSON schema from the SAM's return type using Jandex.
 */
public class AgentFunctionProcessor {
    static {
        System.out.println("[AgentFunctionProcessor] CLASS LOADED");
    }

    private static final DotName AGENT_FUNCTION = DotName.createSimple(AgentFunction.class.getName());
    private static final DotName JSON_PROPERTY = DotName.createSimple(JsonProperty.class.getName());
    
    private final ObjectMapper mapper = new ObjectMapper();

    @BuildStep
    void processAgentFunctions(CombinedIndexBuildItem indexBuildItem,
                               io.quarkus.deployment.annotations.BuildProducer<GeneratedResourceBuildItem> resources,
                               io.quarkus.deployment.annotations.BuildProducer<NativeImageResourceBuildItem> nativeResources,
                               io.quarkus.deployment.annotations.BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) throws IOException {

        IndexView index = indexBuildItem.getIndex();
        System.out.println("[AgentFunctionProcessor] Processing agent functions...");
        System.out.println("[AgentFunctionProcessor] All annotations in index: " + index.getAnnotations(AGENT_FUNCTION).size());

        for (AnnotationInstance ai : index.getAnnotations(AGENT_FUNCTION)) {
            System.out.println("[AgentFunctionProcessor] Found @AgentFunction on: " + ai.target().asClass().name());
            ClassInfo interfaceInfo = ai.target().asClass();
            
            // 1. Find the Single Abstract Method (SAM)
            MethodInfo sam = interfaceInfo.methods().stream()
                .filter(m -> (m.flags() & Modifier.ABSTRACT) != 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("@AgentFunction must be a SAM interface: " + interfaceInfo.name()));

            Type returnType = sam.returnType();
            String schemaName = interfaceInfo.name().withoutPackagePrefix();
            
            ClassInfo returnTypeInfo = index.getClassByName(DotName.createSimple(returnType.name().toString()));
            if (returnTypeInfo == null) {
                throw new IllegalStateException(
                    "Return type " + returnType.name() + " not found in Jandex index. " +
                    "Ensure the module containing this type has a Jandex index " +
                    "(jandex-maven-plugin or META-INF/beans.xml) and is on the augmentation classpath.");
            }
            
            // 2. Generate JSON Schema recursively
            ObjectNode schema = mapper.createObjectNode();
            Set<String> processedTypes = new HashSet<>();
            buildSchema(index, returnType, schema, processedTypes, reflectiveClasses);
            
            if (schema.has("properties") && !schema.get("properties").fieldNames().hasNext()) {
                throw new IllegalStateException(
                    "Schema for " + schemaName + " has no properties. " +
                    "recordComponents() returned empty for " + returnType.name() +
                    ". Check Jandex index contains the return type.");
            }
            
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(schema);
            String path = "arcclaw/schemas/" + schemaName + ".json";
            
            resources.produce(new GeneratedResourceBuildItem(path, bytes));
            nativeResources.produce(new NativeImageResourceBuildItem(path));
            
            // Register the interface itself for reflection
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(interfaceInfo.name().toString()).methods().fields().build());
        }
    }

    private void buildSchema(IndexView index, Type type, ObjectNode node, Set<String> processedTypes,
                             io.quarkus.deployment.annotations.BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        
        String typeName = type.name().toString();
        
        // Basic types
        if (typeName.equals("java.lang.String")) {
            node.put("type", "string");
            return;
        }
        if (typeName.equals("double") || typeName.equals("float") || typeName.equals("java.lang.Double") || typeName.equals("java.lang.Float")) {
            node.put("type", "number");
            return;
        }
        if (typeName.equals("int") || typeName.equals("long") || typeName.equals("java.lang.Integer") || typeName.equals("java.lang.Long")) {
            node.put("type", "integer");
            return;
        }
        if (typeName.equals("boolean") || typeName.equals("java.lang.Boolean")) {
            node.put("type", "boolean");
            return;
        }

        // List/Array
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE && typeName.equals("java.util.List")) {
            node.put("type", "array");
            Type itemType = type.asParameterizedType().arguments().get(0);
            buildSchema(index, itemType, node.putObject("items"), processedTypes, reflectiveClasses);
            return;
        }

        // Record or Class
        if (type.kind() == Type.Kind.CLASS || type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            if (processedTypes.contains(typeName)) {
                node.put("type", "object"); // prevent infinite recursion
                return;
            }
            processedTypes.add(typeName);
            
            ClassInfo classInfo = index.getClassByName(type.name());
            if (classInfo == null) {
                node.put("type", "object");
                return;
            }

            // Register for reflection
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(typeName).methods().fields().build());

            node.put("type", "object");
            ObjectNode properties = node.putObject("properties");
            ArrayNode required = node.putArray("required");

            // Handle Records (Jandex handles record components)
            if (classInfo.isRecord()) {
                for (RecordComponentInfo rc : classInfo.recordComponents()) {
                    ObjectNode prop = properties.putObject(rc.name());
                    buildSchema(index, rc.type(), prop, processedTypes, reflectiveClasses);
                    
                    AnnotationInstance ann = rc.annotation(JSON_PROPERTY);
                    if (ann != null) {
                        AnnotationValue val = ann.value("required");
                        if (val != null && val.asBoolean()) {
                            required.add(rc.name());
                        }
                    }
                }
            } else {
                // Handle regular classes (simplified, only fields)
                classInfo.fields().forEach(f -> {
                    if ((f.flags() & Modifier.STATIC) == 0) {
                        ObjectNode prop = properties.putObject(f.name());
                        buildSchema(index, f.type(), prop, processedTypes, reflectiveClasses);
                        
                        AnnotationInstance ann = f.annotation(JSON_PROPERTY);
                        if (ann != null) {
                            AnnotationValue val = ann.value("required");
                            if (val != null && val.asBoolean()) {
                                required.add(f.name());
                            }
                        }
                    }
                });
            }
            node.put("additionalProperties", false);
        }
    }
}
