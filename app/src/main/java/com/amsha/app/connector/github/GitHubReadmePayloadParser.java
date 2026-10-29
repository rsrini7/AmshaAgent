package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict parser for the small GitHub App and Contents API contracts used by the connector. */
@ApplicationScoped
public class GitHubReadmePayloadParser {

    private static final String ROOT_README_PATH = "README.md";
    private static final int MAX_TOKEN_CHARS = 2048;
    private final ObjectMapper mapper;

    @Inject
    public GitHubReadmePayloadParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    GitHubReadmePayloadParser() {
        this(new ObjectMapper());
    }

    Installation parseInstallation(String json) {
        JsonNode root = parseObject(json);
        long id = positiveLong(root, "id");
        if (!"selected".equals(text(root, "repository_selection", 32))) {
            throw invalidResponse();
        }
        JsonNode permissions = root.path("permissions");
        if (!permissions.isObject() || !"read".equals(text(permissions, "contents", 16))) {
            throw invalidResponse();
        }
        Set<String> permissionNames = new HashSet<>();
        permissions.fieldNames().forEachRemaining(permissionNames::add);
        if (!Set.of("contents", "metadata").containsAll(permissionNames)
                || (permissions.has("metadata") && !"read".equals(text(permissions, "metadata", 16)))) {
            throw invalidResponse();
        }
        return new Installation(id);
    }

    MetadataToken parseMetadataToken(String json) {
        JsonNode root = parseObject(json);
        String token = text(root, "token", MAX_TOKEN_CHARS);
        JsonNode permissions = root.path("permissions");
        if (!permissions.isObject() || permissions.size() != 1 || !"read".equals(text(permissions, "metadata", 16))) {
            throw invalidResponse();
        }
        return new MetadataToken(token);
    }

    InstalledRepository parseInstalledRepositories(String json, String expectedRepository) {
        JsonNode root = parseObject(json);
        JsonNode totalCount = root.path("total_count");
        JsonNode repositories = root.path("repositories");
        if (!totalCount.isIntegralNumber()
                || totalCount.intValue() != 1
                || !repositories.isArray()
                || repositories.size() != 1) {
            throw invalidResponse();
        }
        JsonNode repository = repositories.get(0);
        if (!expectedRepository.equals(text(repository, "full_name", 200))) {
            throw invalidResponse();
        }
        return new InstalledRepository(positiveLong(repository, "id"));
    }

    InstallationToken parseToken(String json, String expectedRepository) {
        JsonNode root = parseObject(json);
        String token = text(root, "token", MAX_TOKEN_CHARS);
        JsonNode permissions = root.path("permissions");
        if (!permissions.isObject() || !"read".equals(text(permissions, "contents", 16))) {
            throw invalidResponse();
        }
        Set<String> permissionNames = new HashSet<>();
        permissions.fieldNames().forEachRemaining(permissionNames::add);
        if (!Set.of("contents", "metadata").containsAll(permissionNames)
                || (permissions.has("metadata") && !"read".equals(text(permissions, "metadata", 16)))) {
            throw invalidResponse();
        }
        JsonNode repositories = root.path("repositories");
        if (!repositories.isArray() || repositories.size() != 1) {
            throw invalidResponse();
        }
        JsonNode repository = repositories.get(0);
        if (!expectedRepository.equals(text(repository, "full_name", 200))) {
            throw invalidResponse();
        }
        return new InstallationToken(token, positiveLong(repository, "id"));
    }

    Repository parseRepository(String json, String expectedRepository) {
        JsonNode root = parseObject(json);
        long id = positiveLong(root, "id");
        if (!expectedRepository.equals(text(root, "full_name", 200))
                || !root.path("private").isBoolean()
                || root.path("private").booleanValue()) {
            throw invalidResponse();
        }
        String defaultBranch = text(root, "default_branch", 255);
        if (defaultBranch.contains("\n") || defaultBranch.contains("\r")) {
            throw invalidResponse();
        }
        return new Repository(id, defaultBranch);
    }

    boolean repositoryIsPrivate(String json) {
        JsonNode value = parseObject(json).path("private");
        if (!value.isBoolean()) {
            throw invalidResponse();
        }
        return value.booleanValue();
    }

    String parseCommitSha(String json) {
        String sha = text(parseObject(json), "sha", 64);
        if (!sha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw invalidResponse();
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    Readme parseReadme(String json, int maxBytes) {
        JsonNode root = parseObject(json);
        if (!ROOT_README_PATH.equals(text(root, "path", 256))
                || !"file".equals(text(root, "type", 32))
                || !"base64".equals(text(root, "encoding", 32))) {
            throw invalidResponse();
        }
        JsonNode sizeNode = root.path("size");
        if (!sizeNode.isIntegralNumber() || !sizeNode.canConvertToInt()) {
            throw invalidResponse();
        }
        int declaredSize = sizeNode.intValue();
        if (declaredSize < 0 || declaredSize > maxBytes) {
            throw invalidResponse();
        }
        String encoded = text(root, "content", (maxBytes * 4 / 3) + 1024);
        byte[] contentBytes = decodeBase64(encoded);
        if (contentBytes.length != declaredSize || contentBytes.length > maxBytes) {
            throw invalidResponse();
        }
        String markdown = decodeUtf8(contentBytes);
        String blobSha = text(root, "sha", 64);
        if (!blobSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw invalidResponse();
        }
        return new Readme(markdown, blobSha.toLowerCase(Locale.ROOT));
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw invalidResponse();
        }
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalidResponse();
            }
            return root;
        } catch (IOException | RuntimeException _) {
            throw invalidResponse();
        }
    }

    private static long positiveLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalidResponse();
        }
        return value.longValue();
    }

    private static String text(JsonNode object, String field, int maxChars) {
        JsonNode value = object.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maxChars) {
            throw invalidResponse();
        }
        return value.textValue();
    }

    private static byte[] decodeBase64(String encoded) {
        StringBuilder compact = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char value = encoded.charAt(index);
            if (Character.isWhitespace(value)) {
                continue;
            }
            if (!(value >= 'A' && value <= 'Z')
                    && !(value >= 'a' && value <= 'z')
                    && !(value >= '0' && value <= '9')
                    && value != '+'
                    && value != '/'
                    && value != '=') {
                throw invalidResponse();
            }
            compact.append(value);
        }
        try {
            return Base64.getDecoder().decode(compact.toString());
        } catch (IllegalArgumentException _) {
            throw invalidResponse();
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer buffer = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return buffer.toString();
        } catch (CharacterCodingException _) {
            throw invalidResponse();
        }
    }

    private static IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException("GitHub response did not match the connector contract");
    }

    record Installation(long id) {}

    record MetadataToken(String value) {
        @Override
        public String toString() {
            return "MetadataToken[value=[redacted]]";
        }
    }

    record InstalledRepository(long id) {}

    record InstallationToken(String value, long repositoryId) {
        @Override
        public String toString() {
            return "InstallationToken[value=[redacted], repositoryId=" + repositoryId + "]";
        }
    }

    record Repository(long id, String defaultBranch) {}

    record Readme(String markdown, String blobSha) {
        @Override
        public String toString() {
            return "Readme[markdown=[redacted], blobSha=" + blobSha + "]";
        }
    }
}
