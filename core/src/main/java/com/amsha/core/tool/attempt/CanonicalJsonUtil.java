package com.amsha.core.tool.attempt;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Utility for RFC 8785 JSON Canonicalization Scheme (JCS) processing,
 * JSON data model normalization, and cryptographic digest derivation.
 */
public final class CanonicalJsonUtil {

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private CanonicalJsonUtil() {}

    /**
     * Canonicalizes a JSON string to RFC 8785 canonical UTF-8 bytes.
     *
     * @param json raw JSON string
     * @return RFC 8785 canonical UTF-8 bytes
     * @throws IllegalArgumentException if json is null or invalid JSON
     */
    public static byte[] canonicalizeToUtf8(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Input JSON string must not be null");
        }
        try {
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to canonicalize JSON per RFC 8785: " + e.getMessage(), e);
        }
    }

    /**
     * Canonicalizes a JSON string to RFC 8785 canonical string.
     *
     * @param json raw JSON string
     * @return RFC 8785 canonical string
     * @throws IllegalArgumentException if json is null or invalid JSON
     */
    public static String canonicalize(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Input JSON string must not be null");
        }
        try {
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to canonicalize JSON per RFC 8785: " + e.getMessage(), e);
        }
    }

    /**
     * Canonicalizes a Jackson {@link JsonNode} tree to RFC 8785 canonical UTF-8 bytes.
     *
     * @param node Jackson JSON node
     * @param mapper Jackson ObjectMapper, or null to use default
     * @return RFC 8785 canonical UTF-8 bytes
     */
    public static byte[] canonicalizeNodeToUtf8(JsonNode node, ObjectMapper mapper) {
        JsonNode targetNode = node != null ? node : NullNode.getInstance();
        try {
            ObjectMapper m = mapper != null ? mapper : DEFAULT_MAPPER;
            String rawJson = m.writeValueAsString(targetNode);
            return canonicalizeToUtf8(rawJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize JsonNode to canonical JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Canonicalizes a Jackson {@link JsonNode} tree to RFC 8785 canonical string.
     *
     * @param node Jackson JSON node
     * @param mapper Jackson ObjectMapper, or null to use default
     * @return RFC 8785 canonical string
     */
    public static String canonicalizeNode(JsonNode node, ObjectMapper mapper) {
        JsonNode targetNode = node != null ? node : NullNode.getInstance();
        try {
            ObjectMapper m = mapper != null ? mapper : DEFAULT_MAPPER;
            String rawJson = m.writeValueAsString(targetNode);
            return canonicalize(rawJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize JsonNode to canonical JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Normalizes an arbitrary object to the RFC 8785 JSON data model.
     * <p>
     * Allowed types:
     * <ul>
     *   <li>Objects with String keys (Map, ObjectNode)</li>
     *   <li>Ordered arrays and lists (List, Object[], primitive arrays, ArrayNode)</li>
     *   <li>Strings (String, TextNode)</li>
     *   <li>Booleans (Boolean, BooleanNode)</li>
     *   <li>Finite numbers (Integer, Long, BigInteger, Double, Float, BigDecimal, Short, Byte)</li>
     *   <li>null (or NullNode)</li>
     * </ul>
     * Non-JSON values (Character, unordered Set, mutable Atomic numbers, NaN, Infinite, cyclic structures, unknown types) fail closed with {@link IllegalArgumentException}.
     * Integral values are preserved in their parsed representation and not coerced to floating point.
     *
     * @param value the raw value to normalize
     * @return a normalized Jackson {@link JsonNode} tree
     */
    public static JsonNode normalize(Object value) {
        Set<Object> activePath = Collections.newSetFromMap(new IdentityHashMap<>());
        return normalizeValue(value, activePath);
    }

    private static JsonNode normalizeValue(Object value, Set<Object> activePath) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value instanceof JsonNode node) {
            return normalizeJsonNode(node, activePath);
        }
        if (value instanceof String str) {
            return TextNode.valueOf(str);
        }
        if (value instanceof Boolean b) {
            return BooleanNode.valueOf(b);
        }
        if (value instanceof Number num) {
            return normalizeNumber(num);
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map, activePath);
        }
        if (value instanceof List<?> list) {
            return normalizeList(list, activePath);
        }
        if (value instanceof Object[] arr) {
            return normalizeObjectArray(arr, activePath);
        }
        if (value.getClass().isArray()) {
            return normalizePrimitiveArray(value);
        }
        throw new IllegalArgumentException(
                "Unsupported non-JSON parameter type: " + value.getClass().getName());
    }

    private static JsonNode normalizeNumber(Number num) {
        if (num instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Non-finite JSON number: " + d);
            }
            return DoubleNode.valueOf(d);
        }
        if (num instanceof Float f) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new IllegalArgumentException("Non-finite JSON number: " + f);
            }
            return FloatNode.valueOf(f);
        }
        if (num instanceof Integer i) {
            return IntNode.valueOf(i);
        }
        if (num instanceof Long l) {
            return LongNode.valueOf(l);
        }
        if (num instanceof BigInteger bi) {
            return BigIntegerNode.valueOf(bi);
        }
        if (num instanceof BigDecimal bd) {
            return DecimalNode.valueOf(bd);
        }
        if (num instanceof Short s) {
            return ShortNode.valueOf(s);
        }
        if (num instanceof Byte by) {
            return IntNode.valueOf(by);
        }
        throw new IllegalArgumentException(
                "Unsupported JSON number type: " + num.getClass().getName());
    }

    private static JsonNode normalizeMap(Map<?, ?> map, Set<Object> activePath) {
        if (!activePath.add(map)) {
            throw new IllegalArgumentException("Cyclic structure detected in parameters");
        }
        try {
            ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (key == null) {
                    throw new IllegalArgumentException("Map key must not be null");
                }
                if (!(key instanceof String strKey)) {
                    throw new IllegalArgumentException(
                            "Map key must be String, got: " + key.getClass().getName());
                }
                objectNode.set(strKey, normalizeValue(entry.getValue(), activePath));
            }
            return objectNode;
        } finally {
            activePath.remove(map);
        }
    }

    private static JsonNode normalizeList(List<?> list, Set<Object> activePath) {
        if (!activePath.add(list)) {
            throw new IllegalArgumentException("Cyclic structure detected in parameters");
        }
        try {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode(list.size());
            for (Object elem : list) {
                arrayNode.add(normalizeValue(elem, activePath));
            }
            return arrayNode;
        } finally {
            activePath.remove(list);
        }
    }

    private static JsonNode normalizeObjectArray(Object[] arr, Set<Object> activePath) {
        if (!activePath.add(arr)) {
            throw new IllegalArgumentException("Cyclic structure detected in parameters");
        }
        try {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode(arr.length);
            for (Object elem : arr) {
                arrayNode.add(normalizeValue(elem, activePath));
            }
            return arrayNode;
        } finally {
            activePath.remove(arr);
        }
    }

    private static JsonNode normalizePrimitiveArray(Object value) {
        if (value instanceof int[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (int v : arr) an.add(v);
            return an;
        }
        if (value instanceof long[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (long v : arr) an.add(v);
            return an;
        }
        if (value instanceof double[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (double v : arr) {
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new IllegalArgumentException("Non-finite JSON number: " + v);
                }
                an.add(v);
            }
            return an;
        }
        if (value instanceof float[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (float v : arr) {
                if (Float.isNaN(v) || Float.isInfinite(v)) {
                    throw new IllegalArgumentException("Non-finite JSON number: " + v);
                }
                an.add(v);
            }
            return an;
        }
        if (value instanceof boolean[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (boolean v : arr) an.add(v);
            return an;
        }
        if (value instanceof short[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (short v : arr) an.add(v);
            return an;
        }
        if (value instanceof byte[] arr) {
            ArrayNode an = JsonNodeFactory.instance.arrayNode(arr.length);
            for (byte v : arr) an.add(v);
            return an;
        }
        throw new IllegalArgumentException(
                "Unsupported array type: " + value.getClass().getName());
    }

    private static JsonNode normalizeJsonNode(JsonNode node, Set<Object> activePath) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }
        if (node.isTextual() || node.isBoolean()) {
            return node;
        }
        if (node.isNumber()) {
            if (node.isDouble()) {
                double d = node.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException("Non-finite JSON number: " + d);
                }
            } else if (node.isFloat()) {
                float f = node.floatValue();
                if (Float.isNaN(f) || Float.isInfinite(f)) {
                    throw new IllegalArgumentException("Non-finite JSON number: " + f);
                }
            }
            return node;
        }
        if (node.isObject()) {
            if (!activePath.add(node)) {
                throw new IllegalArgumentException("Cyclic structure detected in parameters");
            }
            try {
                ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    objectNode.set(field.getKey(), normalizeJsonNode(field.getValue(), activePath));
                }
                return objectNode;
            } finally {
                activePath.remove(node);
            }
        }
        if (node.isArray()) {
            if (!activePath.add(node)) {
                throw new IllegalArgumentException("Cyclic structure detected in parameters");
            }
            try {
                ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode(node.size());
                for (JsonNode elem : node) {
                    arrayNode.add(normalizeJsonNode(elem, activePath));
                }
                return arrayNode;
            } finally {
                activePath.remove(node);
            }
        }
        if (node.isPojo()) {
            Object pojo = ((POJONode) node).getPojo();
            return normalizeValue(pojo, activePath);
        }
        throw new IllegalArgumentException("Unsupported JSON node type: " + node.getNodeType());
    }

    /**
     * Computes the SHA-256 digest of arbitrary byte data.
     *
     * @param data byte array to hash
     * @return 32-byte SHA-256 digest
     */
    public static byte[] sha256(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data to hash must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 message digest algorithm unavailable", e);
        }
    }

    /**
     * Computes the lowercase hex-encoded SHA-256 digest of arbitrary byte data.
     *
     * @param data byte array to hash
     * @return 64-character lowercase hex string
     */
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /**
     * Returns true if the given string is a valid lowercase 64-character hexadecimal digest.
     *
     * @param hex string to check
     * @return true if string consists of exactly 64 lowercase hexadecimal characters ([0-9a-f])
     */
    public static boolean isLowercaseHex64(String hex) {
        if (hex == null || hex.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
