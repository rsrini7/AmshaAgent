package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict parser for the GitHub App, tree, and Contents API contracts used by the connector. */
@ApplicationScoped
public class GitHubReadmePayloadParser {
    private static final int MAX_TOKEN_CHARS = 2048;
    private static final int MAX_TREE_ENTRIES = 20_000;
    private static final int MAX_PATH_CHARS = 1024;

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
                || !totalCount.canConvertToLong()
                || totalCount.longValue() != 1L
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
        String fullName = text(root, "full_name", 200);
        if (!expectedRepository.equals(fullName)) {
            throw invalidResponse();
        }
        JsonNode privateFlag = root.path("private");
        if (!privateFlag.isBoolean()) {
            throw invalidResponse();
        }
        if (privateFlag.booleanValue()) {
            throw new GitHubReadmeSourceException(GitHubReadmeSourceException.Kind.SOURCE_NOT_PUBLIC);
        }
        String defaultBranch = text(root, "default_branch", 255);
        if (defaultBranch.contains("\n") || defaultBranch.contains("\r")) {
            throw invalidResponse();
        }
        return new Repository(id, fullName, defaultBranch);
    }

    String parseCommitSha(String json) {
        String sha = text(parseObject(json), "sha", 64);
        if (!sha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw invalidResponse();
        }
        return sha.toLowerCase(Locale.ROOT);
    }

    List<TreeEntry> parseTree(String json) {
        JsonNode root = parseObject(json);
        JsonNode truncated = root.path("truncated");
        JsonNode tree = root.path("tree");
        if (!truncated.isBoolean() || truncated.booleanValue() || !tree.isArray() || tree.size() > MAX_TREE_ENTRIES) {
            throw invalidResponse();
        }
        ArrayList<TreeEntry> entries = new ArrayList<>(tree.size());
        Set<String> paths = new HashSet<>();
        for (JsonNode entry : tree) {
            String path = text(entry, "path", MAX_PATH_CHARS);
            String type = text(entry, "type", 16);
            String mode = text(entry, "mode", 8);
            String sha = text(entry, "sha", 64).toLowerCase(Locale.ROOT);
            if (!GitHubReadmeSourcePolicy.isSafeRelativePath(path)
                    || !paths.add(path)
                    || !sha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
                throw invalidResponse();
            }
            long size = 0;
            if ("blob".equals(type)) {
                JsonNode sizeNode = entry.path("size");
                if (!sizeNode.isIntegralNumber() || !sizeNode.canConvertToLong() || sizeNode.longValue() < 0) {
                    throw invalidResponse();
                }
                size = sizeNode.longValue();
            } else if (!"tree".equals(type) && !"commit".equals(type)) {
                throw invalidResponse();
            }
            if (!validMode(type, mode)) {
                throw invalidResponse();
            }
            entries.add(new TreeEntry(path, type, mode, size, sha));
        }
        return List.copyOf(entries);
    }

    Readme parseDocument(String json, String expectedPath, int maxBytes) {
        JsonNode root = parseObject(json);
        if (!expectedPath.equals(text(root, "path", MAX_PATH_CHARS))
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
        return new Readme(expectedPath, markdown, blobSha.toLowerCase(Locale.ROOT));
    }

    private static boolean validMode(String type, String mode) {
        return switch (type) {
            case "blob" -> Set.of("100644", "100755", "120000").contains(mode);
            case "tree" -> "040000".equals(mode);
            case "commit" -> "160000".equals(mode);
            default -> false;
        };
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
        if (object == null || !object.isObject()) {
            throw invalidResponse();
        }
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
        return new IllegalArgumentException("GitHub response did not match connector contract");
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

    record Repository(long id, String fullName, String defaultBranch) {}

    record TreeEntry(String path, String type, String mode, long size, String sha) {}

    record Readme(String path, String markdown, String blobSha) {
        @Override
        public String toString() {
            return "Readme[path=" + path + ", markdown=[redacted], blobSha=" + blobSha + "]";
        }
    }
}
