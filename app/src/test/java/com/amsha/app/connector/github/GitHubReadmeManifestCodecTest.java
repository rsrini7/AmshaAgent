package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class GitHubReadmeManifestCodecTest {
    private static final String COMMIT_ONE = "1111111111111111111111111111111111111111";
    private static final String COMMIT_TWO = "2222222222222222222222222222222222222222";
    private static final String README_BLOB = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DOC_BLOB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubReadmeManifestCodec codec = new GitHubReadmeManifestCodec(mapper);
    private final GitHubReadmeConnectionSettings settings = GitHubReadmeSourcePolicy.firstConnection();
    private final String namespace = GitHubReadmeIndexKeys.userNamespace("owner@example.test", "workspace-a");

    @Test
    void roundTripsMultipleDocumentsWithPathCommitBlobAndPendingDeletionIdentity() {
        String readmeId = GitHubReadmeIndexKeys.documentId(namespace, 42, "README.md", COMMIT_ONE);
        String docsId = GitHubReadmeIndexKeys.documentId(namespace, 42, "docs/architecture.md", COMMIT_ONE);
        String retiredId = GitHubReadmeIndexKeys.documentId(namespace, 42, "docs/removed.md", COMMIT_TWO);
        GitHubReadmeManifestCodec.Manifest manifest = new GitHubReadmeManifestCodec.Manifest(
                42,
                "rsrini7/AmshaAgent",
                settings,
                "main",
                COMMIT_ONE,
                List.of(
                        new GitHubReadmeManifestCodec.ManifestDocument("README.md", COMMIT_ONE, README_BLOB, readmeId),
                        new GitHubReadmeManifestCodec.ManifestDocument(
                                "docs/architecture.md", COMMIT_ONE, DOC_BLOB, docsId)),
                List.of(retiredId),
                Instant.parse("2026-10-29T12:30:00Z"));

        GitHubReadmeManifestCodec.Manifest parsed = codec.parse(codec.write(manifest), namespace);

        assertEquals(manifest, parsed);
        assertEquals("docs/architecture.md", parsed.documents().get(1).path());
        assertEquals(COMMIT_ONE, parsed.documents().get(1).commitSha());
        assertEquals(DOC_BLOB, parsed.documents().get(1).blobSha());
        assertEquals(List.of(retiredId), parsed.pendingDeletionIds());
    }

    @Test
    void rejectsDuplicatePathsUnknownReferencesAndUnknownFields() throws Exception {
        GitHubReadmeManifestCodec.Manifest manifest = validManifest();
        ObjectNode duplicatePath = (ObjectNode) mapper.readTree(codec.write(manifest));
        ObjectNode secondDocument = (ObjectNode) duplicatePath.path("documents").get(1);
        secondDocument.put("path", "README.md");
        secondDocument.put("documentId", GitHubReadmeIndexKeys.documentId(namespace, 42, "README.md", COMMIT_ONE));
        assertThrows(GitHubReadmeConnectorException.class, () -> codec.parse(duplicatePath.toString(), namespace));

        ObjectNode unknownReference = (ObjectNode) mapper.readTree(codec.write(manifest));
        ((ObjectNode) unknownReference.path("documents").get(0)).put("documentId", "other-document-id");
        assertThrows(GitHubReadmeConnectorException.class, () -> codec.parse(unknownReference.toString(), namespace));

        ObjectNode unknownField = (ObjectNode) mapper.readTree(codec.write(manifest));
        unknownField.put("branchOverride", "attacker-branch");
        assertThrows(GitHubReadmeConnectorException.class, () -> codec.parse(unknownField.toString(), namespace));
    }

    @Test
    void rejectsPendingDeletionThatAliasesAnActiveDocument() throws Exception {
        GitHubReadmeManifestCodec.Manifest manifest = validManifest();
        ObjectNode aliasedPending = (ObjectNode) mapper.readTree(codec.write(manifest));
        aliasedPending
                .putArray("pendingDeletionIds")
                .add(manifest.documents().getFirst().documentId());

        assertThrows(GitHubReadmeConnectorException.class, () -> codec.parse(aliasedPending.toString(), namespace));
    }

    private GitHubReadmeManifestCodec.Manifest validManifest() {
        return new GitHubReadmeManifestCodec.Manifest(
                42,
                "rsrini7/AmshaAgent",
                settings,
                "main",
                COMMIT_ONE,
                List.of(
                        new GitHubReadmeManifestCodec.ManifestDocument(
                                "README.md",
                                COMMIT_ONE,
                                README_BLOB,
                                GitHubReadmeIndexKeys.documentId(namespace, 42, "README.md", COMMIT_ONE)),
                        new GitHubReadmeManifestCodec.ManifestDocument(
                                "docs/architecture.md",
                                COMMIT_ONE,
                                DOC_BLOB,
                                GitHubReadmeIndexKeys.documentId(namespace, 42, "docs/architecture.md", COMMIT_ONE))),
                List.of(),
                Instant.parse("2026-10-29T12:30:00Z"));
    }
}
