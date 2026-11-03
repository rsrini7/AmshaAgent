package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActionFingerprintGeneratorTest {

    private ActionFingerprintGenerator generator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        generator = new ActionFingerprintGenerator(objectMapper);
    }

    @Test
    @DisplayName("Amsha Golden Vector: Fixed parameters produce exact expected fingerprint")
    void testGoldenVectorWithParameters() {
        String workspaceId = "ws-default";
        String sessionId = "session-123";
        String toolName = "file_read";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", "/tmp/test.txt");
        params.put("offset", 0);

        String canonicalJson = generator.canonicalJson(workspaceId, sessionId, toolName, params);
        assertEquals(
                "{\"parameters\":{\"offset\":0,\"path\":\"/tmp/test.txt\"},\"sessionId\":\"session-123\",\"toolName\":\"file_read\",\"workspaceId\":\"ws-default\"}",
                canonicalJson);

        String fingerprint = generator.generate(workspaceId, sessionId, toolName, params);
        assertEquals("sha256:v1:9e0b448cea2363b267f6833eeda8f1b35b090dfdc76ac0b732fd3dd3180fba3f", fingerprint);
    }

    @Test
    @DisplayName("Amsha Golden Vector: Null parameters produce exact expected fingerprint")
    void testGoldenVectorNullParameters() {
        String workspaceId = "ws-default";
        String sessionId = "session-123";
        String toolName = "file_read";

        String canonicalJson = generator.canonicalJson(workspaceId, sessionId, toolName, (Map<String, Object>) null);
        assertEquals(
                "{\"parameters\":null,\"sessionId\":\"session-123\",\"toolName\":\"file_read\",\"workspaceId\":\"ws-default\"}",
                canonicalJson);

        String fingerprint = generator.generate(workspaceId, sessionId, toolName, (Map<String, Object>) null);
        assertEquals("sha256:v1:95fc498e60cc900ce77a197655f1a55e33accacc66dadf1ba72f4549d9ad8bd9", fingerprint);

        // JsonNode overload parity
        String jsonNodeFingerprint = generator.generate(workspaceId, sessionId, toolName, (JsonNode) null);
        assertEquals(fingerprint, jsonNodeFingerprint);

        String nullNodeFingerprint = generator.generate(workspaceId, sessionId, toolName, NullNode.getInstance());
        assertEquals(fingerprint, nullNodeFingerprint);
    }

    @Test
    @DisplayName("Amsha Golden Vector: Empty parameters produce exact expected fingerprint")
    void testGoldenVectorEmptyParameters() {
        String workspaceId = "ws-default";
        String sessionId = "session-123";
        String toolName = "file_read";

        String canonicalJson = generator.canonicalJson(workspaceId, sessionId, toolName, Collections.emptyMap());
        assertEquals(
                "{\"parameters\":{},\"sessionId\":\"session-123\",\"toolName\":\"file_read\",\"workspaceId\":\"ws-default\"}",
                canonicalJson);

        String fingerprint = generator.generate(workspaceId, sessionId, toolName, Collections.emptyMap());
        assertEquals("sha256:v1:eede773d5f43b16ebe208a34846dc8cc8ab138cd93d91e3d62e274df043aa36e", fingerprint);

        ObjectNode emptyNode = objectMapper.createObjectNode();
        assertEquals(fingerprint, generator.generate(workspaceId, sessionId, toolName, emptyNode));
    }

    @Test
    @DisplayName("Key order invariance: Arbitrary map key insertion produces identical fingerprint")
    void testKeyOrderInvariance() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("z", 100);
        map1.put("a", 200);
        map1.put("m", Map.of("nested_b", 2, "nested_a", 1));

        Map<String, Object> map2 = new TreeMap<>();
        map2.put("a", 200);
        map2.put("z", 100);
        map2.put("m", Map.of("nested_a", 1, "nested_b", 2));

        String fp1 = generator.generate("ws-1", "s-1", "test_tool", map1);
        String fp2 = generator.generate("ws-1", "s-1", "test_tool", map2);

        assertEquals(fp1, fp2);
    }

    @Test
    @DisplayName("Number type preservation: Integral and floating point values")
    void testNumberTypePreservation() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("int", 42);
        map.put("long", 9007199254740991L);
        map.put("bigint", new BigInteger("12345678901234567890"));
        map.put("double", 3.14159);
        map.put("decimal", new BigDecimal("123.45"));

        String canonicalJson = generator.canonicalJson("ws", "sess", "calc", map);
        assertTrue(canonicalJson.contains("\"int\":42"));
        assertTrue(canonicalJson.contains("\"long\":9007199254740991"));
        assertTrue(canonicalJson.contains("\"double\":3.14159"));

        String fp = generator.generate("ws", "sess", "calc", map);
        assertNotNull(fp);
        assertTrue(fp.startsWith(ActionFingerprintGenerator.PREFIX));
    }

    @Test
    @DisplayName("Parity: Map and Jackson JsonNode produce identical canonical JSON and fingerprint")
    void testMapAndJsonNodeParity() {
        Map<String, Object> map = Map.of("query", "SELECT * FROM users", "limit", 50, "active", true);

        ObjectNode jsonNode = objectMapper.createObjectNode();
        jsonNode.put("query", "SELECT * FROM users");
        jsonNode.put("limit", 50);
        jsonNode.put("active", true);

        String fpMap = generator.generate("ws", "s", "sql", map);
        String fpNode = generator.generate("ws", "s", "sql", jsonNode);

        assertEquals(fpMap, fpNode);
        assertEquals(
                generator.canonicalJson("ws", "s", "sql", map), generator.canonicalJson("ws", "s", "sql", jsonNode));
    }

    @Test
    @DisplayName("Fail closed: NaN and Infinite floats throw IllegalArgumentException")
    void testNonFiniteNumbersFailClosed() {
        assertThrows(
                IllegalArgumentException.class, () -> generator.generate("w", "s", "t", Map.of("bad", Double.NaN)));
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("w", "s", "t", Map.of("bad", Double.POSITIVE_INFINITY)));
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("w", "s", "t", Map.of("bad", Double.NEGATIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", Map.of("bad", Float.NaN)));
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("w", "s", "t", Map.of("bad", Float.POSITIVE_INFINITY)));

        ObjectNode nodeWithNan = objectMapper.createObjectNode();
        nodeWithNan.set("bad", DoubleNode.valueOf(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", nodeWithNan));
    }

    @Test
    @DisplayName("Fail closed: Cyclic structures throw IllegalArgumentException")
    void testCyclicStructuresFailClosed() {
        Map<String, Object> cyclicMap = new HashMap<>();
        cyclicMap.put("self", cyclicMap);
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", cyclicMap));

        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);
        Map<String, Object> mapWithCyclicList = Map.of("items", cyclicList);
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", mapWithCyclicList));

        Map<String, Object> crossA = new HashMap<>();
        Map<String, Object> crossB = new HashMap<>();
        crossA.put("b", crossB);
        crossB.put("a", crossA);
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", crossA));
    }

    @Test
    @DisplayName("Fail closed: Non-string map keys throw IllegalArgumentException")
    void testNonStringMapKeysFailClosed() {
        Map<Object, Object> invalidKeyMap = new HashMap<>();
        invalidKeyMap.put(123, "number key");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.normalize(invalidKeyMap));

        Map<Object, Object> nullKeyMap = new HashMap<>();
        nullKeyMap.put(null, "null key");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJsonUtil.normalize(nullKeyMap));
    }

    @Test
    @DisplayName("Fail closed: Unsupported non-JSON types throw IllegalArgumentException")
    void testUnsupportedTypesFailClosed() {
        Map<String, Object> mapWithDate = Map.of("time", new Date());
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", mapWithDate));

        Map<String, Object> mapWithThread = Map.of("thread", Thread.currentThread());
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "t", mapWithThread));
    }

    @Test
    @DisplayName("Fail closed: Null or blank workspace, session, or tool name throw IllegalArgumentException")
    void testEnvelopeValidation() {
        Map<String, Object> params = Map.of("k", "v");
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null, "s", "t", params));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("", "s", "t", params));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("  ", "s", "t", params));

        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", null, "t", params));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "", "t", params));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "   ", "t", params));

        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", null, params));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "", params));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("w", "s", "\t", params));
    }

    @Test
    @DisplayName("Fingerprint format verification: sha256:v1:<64-lowercase-hex>")
    void testFingerprintFormat() {
        String fp = generator.generate("w", "s", "t", Map.of("foo", "bar"));
        assertTrue(fp.startsWith("sha256:v1:"));
        assertEquals(10 + 64, fp.length());

        String hex = fp.substring("sha256:v1:".length());
        assertEquals(hex.toLowerCase(), hex);
        assertTrue(hex.matches("^[0-9a-f]{64}$"));
    }

    @Test
    @DisplayName("Static vs Instance parity: computeFingerprint matches instance generate")
    void testStaticHelperParity() {
        Map<String, Object> params = Map.of("action", "run", "dry_run", false);
        String instanceFp = generator.generate("ws", "s", "cli", params);
        String staticFp = ActionFingerprintGenerator.computeFingerprint("ws", "s", "cli", params);

        assertEquals(instanceFp, staticFp);
    }

    @Test
    @DisplayName("Fail closed: Character values are rejected per §4.1 fail-closed rule")
    void testFailClosedOnCharacter() {
        Map<String, Object> params = new HashMap<>();
        params.put("char_val", 'x');
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> generator.generate("ws", "s", "tool", params));
        assertTrue(ex.getMessage().contains("Unsupported non-JSON parameter type"));

        Map<String, Object> paramsBoxed = new HashMap<>();
        paramsBoxed.put("char_boxed", Character.valueOf('z'));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("ws", "s", "tool", paramsBoxed));
    }

    @Test
    @DisplayName("Fail closed: Unordered Set collections are rejected per §4.1 fail-closed rule")
    void testFailClosedOnUnorderedSet() {
        Map<String, Object> params = new HashMap<>();
        params.put("tags", Set.of("alpha", "beta"));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> generator.generate("ws", "s", "tool", params));
        assertTrue(ex.getMessage().contains("Unsupported non-JSON parameter type"));

        Map<String, Object> hashSetParams = new HashMap<>();
        hashSetParams.put("items", new HashSet<>(List.of(1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("ws", "s", "tool", hashSetParams));
    }

    @Test
    @DisplayName("Fail closed: Mutable Atomic numbers are rejected per §4.1 fail-closed rule")
    void testFailClosedOnAtomicNumbers() {
        Map<String, Object> paramsInt = new HashMap<>();
        paramsInt.put("counter", new AtomicInteger(42));
        IllegalArgumentException exInt =
                assertThrows(IllegalArgumentException.class, () -> generator.generate("ws", "s", "tool", paramsInt));
        assertTrue(exInt.getMessage().contains("Unsupported JSON number type"));

        Map<String, Object> paramsLong = new HashMap<>();
        paramsLong.put("long_counter", new AtomicLong(100L));
        IllegalArgumentException exLong =
                assertThrows(IllegalArgumentException.class, () -> generator.generate("ws", "s", "tool", paramsLong));
        assertTrue(exLong.getMessage().contains("Unsupported JSON number type"));
    }
}
