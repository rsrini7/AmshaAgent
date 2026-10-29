package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict, payload-safe parser for owner-supplied GitHub connection settings. */
@ApplicationScoped
public class GitHubReadmeConnectionSettingsParser {
    private static final int MAX_REQUEST_BYTES = 4096;

    private final ObjectMapper mapper;

    @Inject
    public GitHubReadmeConnectionSettingsParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    GitHubReadmeConnectionSettingsParser() {
        this(new ObjectMapper());
    }

    public GitHubReadmeConnectionSettings parse(InputStream input) {
        if (input == null) {
            throw invalidSettings();
        }
        try {
            byte[] body = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length == 0 || body.length > MAX_REQUEST_BYTES) {
                throw invalidSettings();
            }
            String json = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            return parse(json);
        } catch (IOException _) {
            throw invalidSettings();
        }
    }

    GitHubReadmeConnectionSettings parse(String json) {
        JsonNode root = parseObject(json);
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("repository", "allowedPaths"))) {
            throw invalidSettings();
        }
        JsonNode repository = root.path("repository");
        JsonNode paths = root.path("allowedPaths");
        if (!repository.isTextual() || !paths.isArray() || paths.isEmpty() || paths.size() > 8) {
            throw invalidSettings();
        }
        ArrayList<String> allowedPaths = new ArrayList<>(paths.size());
        Set<String> uniquePaths = new HashSet<>();
        for (JsonNode path : paths) {
            if (!path.isTextual()
                    || path.textValue().isBlank()
                    || path.textValue().length() > 256) {
                throw invalidSettings();
            }
            if (!uniquePaths.add(path.textValue())) {
                throw invalidSettings();
            }
            allowedPaths.add(path.textValue());
        }
        try {
            return GitHubReadmeSourcePolicy.validateFirstConnection(
                    new GitHubReadmeConnectionSettings(repository.textValue(), allowedPaths));
        } catch (IllegalArgumentException _) {
            throw invalidSettings();
        }
    }

    private JsonNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw invalidSettings();
        }
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalidSettings();
            }
            return root;
        } catch (IOException | RuntimeException _) {
            throw invalidSettings();
        }
    }

    private static IllegalArgumentException invalidSettings() {
        return new IllegalArgumentException("Invalid GitHub connection settings");
    }
}
