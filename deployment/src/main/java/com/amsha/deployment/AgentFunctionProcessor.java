package com.amsha.deployment;

import static java.util.Map.entry;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.amsha.channel.api.structured.AgentFunction;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.RecordComponentInfo;
import org.jboss.jandex.Type;

import io.quarkus.deployment.annotations.BuildProducer;
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

    private static final DotName AGENT_FUNCTION = DotName.createSimple(AgentFunction.class.getName());
    private static final DotName JSON_PROPERTY = DotName.createSimple(JsonProperty.class.getName());
    private static final String SCHEMA_PROPERTIES = "properties";

    private static final Map<String, String> PRIMITIVE_TYPES = Map.ofEntries(
            entry("java.lang.String", "string"),
            entry("java.lang.Double", "number"),
            entry("java.lang.Float", "number"),
            entry("java.lang.Integer", "integer"),
            entry("java.lang.Long", "integer"),
            entry("java.lang.Boolean", "boolean"),
            entry("double", "number"),
            entry("float", "number"),
            entry("int", "integer"),
            entry("long", "integer"),
            entry("boolean", "boolean"));

    private final ObjectMapper mapper = new ObjectMapper();

    @BuildStep
    void processAgentFunctions(
            CombinedIndexBuildItem indexBuildItem,
            BuildProducer<GeneratedResourceBuildItem> resources,
            BuildProducer<NativeImageResourceBuildItem> nativeResources,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses)
            throws IOException {

        IndexView index = indexBuildItem.getIndex();

        for (AnnotationInstance ai : index.getAnnotations(AGENT_FUNCTION)) {
            ClassInfo interfaceInfo = ai.target().asClass();

            MethodInfo sam = interfaceInfo.methods().stream()
                    .filter(m -> (m.flags() & Modifier.ABSTRACT) != 0)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "@AgentFunction must be a SAM interface: " + interfaceInfo.name()));

            Type returnType = sam.returnType();
            String schemaName = interfaceInfo.name().withoutPackagePrefix();

            ClassInfo returnTypeInfo =
                    index.getClassByName(DotName.createSimple(returnType.name().toString()));
            if (returnTypeInfo == null) {
                throw new IllegalStateException("Return type " + returnType.name() + " not found in Jandex index. "
                        + "Ensure the module containing this type has a Jandex index "
                        + "(jandex-maven-plugin or META-INF/beans.xml) and is on the augmentation classpath.");
            }

            ObjectNode schema = mapper.createObjectNode();
            Set<String> processedTypes = new HashSet<>();
            buildSchema(index, returnType, schema, processedTypes, reflectiveClasses);

            if (schema.has(SCHEMA_PROPERTIES)
                    && !schema.get(SCHEMA_PROPERTIES).fieldNames().hasNext()) {
                throw new IllegalStateException(
                        "Schema for " + schemaName + " has no properties. " + "recordComponents() returned empty for "
                                + returnType.name() + ". Check Jandex index contains the return type.");
            }

            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(schema);
            String path = "amsha/schemas/" + schemaName + ".json";

            resources.produce(new GeneratedResourceBuildItem(path, bytes));
            nativeResources.produce(new NativeImageResourceBuildItem(path));

            reflectiveClasses.produce(
                    ReflectiveClassBuildItem.builder(interfaceInfo.name().toString())
                            .methods()
                            .fields()
                            .build());
        }
    }

    private void buildSchema(
            IndexView index,
            Type type,
            ObjectNode node,
            Set<String> processedTypes,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {

        String typeName = type.name().toString();

        if (tryResolvePrimitive(typeName, node)) {
            return;
        }

        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE && typeName.equals("java.util.List")) {
            buildArraySchema(index, type, node, processedTypes, reflectiveClasses);
            return;
        }

        if (type.kind() == Type.Kind.CLASS || type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            buildObjectSchema(index, type, typeName, node, processedTypes, reflectiveClasses);
        }
    }

    /**
     * Sets the JSON Schema type for primitive/wrapper types.
     * Returns true if the type was handled, false if it's a complex type.
     */
    private boolean tryResolvePrimitive(String typeName, ObjectNode node) {
        String jsonType = PRIMITIVE_TYPES.get(typeName);
        if (jsonType != null) {
            node.put("type", jsonType);
            return true;
        }
        return false;
    }

    private void buildArraySchema(
            IndexView index,
            Type type,
            ObjectNode node,
            Set<String> processedTypes,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        node.put("type", "array");
        Type itemType = type.asParameterizedType().arguments().get(0);
        buildSchema(index, itemType, node.putObject("items"), processedTypes, reflectiveClasses);
    }

    @SuppressWarnings("java:S1192")
    private void buildObjectSchema(
            IndexView index,
            Type type,
            String typeName,
            ObjectNode node,
            Set<String> processedTypes,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {

        if (processedTypes.contains(typeName)) {
            node.put("type", "object");
            return;
        }
        processedTypes.add(typeName);

        ClassInfo classInfo = index.getClassByName(type.name());
        if (classInfo == null) {
            node.put("type", "object");
            return;
        }

        reflectiveClasses.produce(
                ReflectiveClassBuildItem.builder(typeName).methods().fields().build());

        node.put("type", "object");
        ObjectNode properties = node.putObject("properties");
        ArrayNode required = node.putArray("required");

        if (classInfo.isRecord()) {
            for (RecordComponentInfo rc : classInfo.recordComponents()) {
                addProperty(
                        index,
                        classInfo,
                        rc.name(),
                        rc.type(),
                        properties,
                        required,
                        processedTypes,
                        reflectiveClasses);
            }
        } else {
            classInfo.fields().forEach(f -> {
                if ((f.flags() & Modifier.STATIC) == 0) {
                    addProperty(
                            index,
                            classInfo,
                            f.name(),
                            f.type(),
                            properties,
                            required,
                            processedTypes,
                            reflectiveClasses);
                }
            });
        }
        node.put("additionalProperties", false);
    }

    private void addProperty(
            IndexView index,
            ClassInfo containingClass,
            String name,
            Type type,
            ObjectNode properties,
            ArrayNode required,
            Set<String> processedTypes,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        ObjectNode prop = properties.putObject(name);
        buildSchema(index, type, prop, processedTypes, reflectiveClasses);

        // Look up @JsonProperty on the containing class's record component or field, not on the type's class
        AnnotationInstance ann = null;
        if (containingClass.isRecord()) {
            for (RecordComponentInfo rc : containingClass.recordComponents()) {
                if (rc.name().equals(name)) {
                    ann = rc.annotation(JSON_PROPERTY);
                    break;
                }
            }
        } else {
            for (var f : containingClass.fields()) {
                if (f.name().equals(name)) {
                    ann = f.annotation(JSON_PROPERTY);
                    break;
                }
            }
        }
        if (ann != null) {
            AnnotationValue val = ann.value("required");
            if (val != null && val.asBoolean()) {
                required.add(name);
            }
        }
    }
}
