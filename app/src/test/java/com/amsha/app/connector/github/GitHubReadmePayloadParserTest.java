package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class GitHubReadmePayloadParserTest {
    private static final String REPOSITORY = "rsrini7/AmshaAgent";
    private static final String SHA = "0123456789012345678901234567890123456789";
    private static final String MARKDOWN = "# Project docs\nConnector overview.";

    private final GitHubReadmePayloadParser parser = new GitHubReadmePayloadParser();

    @Test
    void validatesSelectedRepositoryInstallationAndContentsOnlyToken() {
        GitHubReadmePayloadParser.Installation installation =
                parser.parseInstallation("{\"id\":481516,\"repository_selection\":\"selected\","
                        + "\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"}}");
        assertEquals(481516, installation.id());

        GitHubReadmePayloadParser.MetadataToken metadata =
                parser.parseMetadataToken("{\"token\":\"metadata-fixture\",\"permissions\":{\"metadata\":\"read\"}}");
        assertEquals("metadata-fixture", metadata.value());

        GitHubReadmePayloadParser.InstalledRepository installed = parser.parseInstalledRepositories(
                "{\"total_count\":1,\"repositories\":[{\"id\":42,\"full_name\":\"" + REPOSITORY + "\"}]}", REPOSITORY);
        assertEquals(42, installed.id());

        GitHubReadmePayloadParser.InstallationToken token = parser.parseToken(
                "{\"token\":\"token-fixture\",\"permissions\":{\"contents\":\"read\"},"
                        + "\"repositories\":[{\"id\":42,\"full_name\":\"" + REPOSITORY + "\"}]}",
                REPOSITORY);
        assertEquals("token-fixture", token.value());
        assertEquals(42, token.repositoryId());
    }

    @Test
    void rejectsBroadInstallationsPermissionsAndUnknownRepositories() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseInstallation(
                        "{\"id\":7,\"repository_selection\":\"all\"," + "\"permissions\":{\"contents\":\"read\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseInstallation("{\"id\":7,\"repository_selection\":\"selected\","
                        + "\"permissions\":{\"contents\":\"write\",\"metadata\":\"read\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseMetadataToken(
                        "{\"token\":\"fixture\"," + "\"permissions\":{\"metadata\":\"read\",\"contents\":\"read\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseInstalledRepositories(
                        "{\"total_count\":2,\"repositories\":[{\"id\":42,\"full_name\":\"" + REPOSITORY + "\"}]}",
                        REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseInstalledRepositories(
                        "{\"total_count\":1,\"repositories\":[{\"id\":42,\"full_name\":\"other/repo\"}]}", REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseToken(
                        "{\"token\":\"fixture\",\"permissions\":{\"contents\":\"write\"},"
                                + "\"repositories\":[{\"id\":42,\"full_name\":\"" + REPOSITORY + "\"}]}",
                        REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseToken(
                        "{\"token\":\"fixture\",\"permissions\":{\"contents\":\"read\"},"
                                + "\"repositories\":[{\"id\":42,\"full_name\":\"" + REPOSITORY + "\"},"
                                + "{\"id\":43,\"full_name\":\"other/repo\"}]}",
                        REPOSITORY));
    }

    @Test
    void correlatesDefaultBranchCommitTreePathAndBlobBeforeReturningMarkdown() {
        GitHubReadmePayloadParser.Repository repository = parser.parseRepository(
                "{\"id\":42,\"full_name\":\"" + REPOSITORY + "\",\"private\":false,\"default_branch\":\"main\"}",
                REPOSITORY);
        assertEquals(42, repository.id());
        assertEquals("main", repository.defaultBranch());
        assertEquals(SHA, parser.parseCommitSha("{\"sha\":\"" + SHA + "\"}"));

        List<GitHubReadmePayloadParser.TreeEntry> entries = parser.parseTree("{\"truncated\":false,\"tree\":["
                + "{\"path\":\"README.md\",\"type\":\"blob\",\"mode\":\"100644\","
                + "\"sha\":\"" + SHA + "\",\"size\":31},"
                + "{\"path\":\"docs/architecture.md\",\"type\":\"blob\",\"mode\":\"100644\","
                + "\"sha\":\"" + SHA + "\",\"size\":31}]}");
        assertEquals(
                List.of("README.md", "docs/architecture.md"),
                entries.stream().map(GitHubReadmePayloadParser.TreeEntry::path).toList());
        GitHubReadmePayloadParser.Readme readme = parser.parseDocument(
                readmeJson("docs/architecture.md", "file", MARKDOWN, SHA), "docs/architecture.md", 4096);
        assertEquals("docs/architecture.md", readme.path());
        assertEquals(MARKDOWN, readme.markdown());
        assertEquals(SHA, readme.blobSha());
    }

    @Test
    void rejectsMalformedDuplicateTruncatedOrUnknownTreeEntries() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseTree("{\"truncated\":true,\"tree\":[]}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseTree("{\"truncated\":false,\"tree\":["
                        + "{\"path\":\"docs/a.md\",\"type\":\"blob\",\"mode\":\"100644\",\"sha\":\"" + SHA
                        + "\",\"size\":1},{\"path\":\"docs/a.md\",\"type\":\"blob\",\"mode\":\"100644\","
                        + "\"sha\":\"" + SHA + "\",\"size\":1}]}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseTree("{\"truncated\":false,\"tree\":["
                        + "{\"path\":\"docs/../secret.md\",\"type\":\"blob\",\"mode\":\"100644\","
                        + "\"sha\":\"" + SHA + "\",\"size\":1}]}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseTree("{\"truncated\":false,\"tree\":["
                        + "{\"path\":\"docs/a.md\",\"type\":\"symlink\",\"mode\":\"120000\","
                        + "\"sha\":\"" + SHA + "\",\"size\":1}]}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseTree("{\"truncated\":false,\"truncated\":false,\"tree\":[]}"));
    }

    @Test
    void rejectsMalformedContentTypesPathsBlobReferencesAndOversizedBodies() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseRepository(
                        "{\"id\":\"42\",\"full_name\":\"" + REPOSITORY
                                + "\",\"private\":false,\"default_branch\":\"main\"}",
                        REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseRepository(
                        "{\"id\":42,\"full_name\":\"other/repo\"," + "\"private\":false,\"default_branch\":\"main\"}",
                        REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseDocument(readmeJson("README.md", "file", MARKDOWN, SHA), "docs/README.md", 4096));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseDocument(readmeJson("README.md", "file", MARKDOWN, "bad"), "README.md", 4096));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseDocument(readmeJson("README.md", "file", MARKDOWN, SHA), "README.md", 8));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseDocument(
                        "{\"path\":\"README.md\",\"type\":\"file\"," + "\"encoding\":\"base64\",\"size\":3,\"sha\":\""
                                + SHA + "\",\"content\":\"%%%\"}",
                        "README.md",
                        4096));
        assertTrue(parser.parseDocument(readmeJson("README.md", "file", MARKDOWN, SHA), "README.md", 4096)
                .markdown()
                .startsWith("# Project"));
    }

    @Test
    void rejectsPrivateRepositoriesAndRepositoryCountsThatOverflowInt() {
        GitHubReadmeSourceException privateSource = assertThrows(
                GitHubReadmeSourceException.class,
                () -> parser.parseRepository(
                        "{\"id\":42,\"full_name\":\"" + REPOSITORY + "\",\"private\":true,\"default_branch\":\"main\"}",
                        REPOSITORY));
        assertEquals(GitHubReadmeSourceException.Kind.SOURCE_NOT_PUBLIC, privateSource.kind());
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseRepository(
                        "{\"id\":42,\"full_name\":\"" + REPOSITORY + "\",\"default_branch\":\"main\"}", REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parseInstalledRepositories(
                        "{\"total_count\":4294967297,\"repositories\":[" + "{\"id\":42,\"full_name\":\"" + REPOSITORY
                                + "\"}]}",
                        REPOSITORY));
    }

    private static String readmeJson(String path, String type, String markdown, String blobSha) {
        byte[] content = markdown.getBytes(StandardCharsets.UTF_8);
        return "{\"path\":\"" + path + "\",\"type\":\"" + type + "\",\"encoding\":\"base64\",\"size\":"
                + content.length + ",\"sha\":\"" + blobSha + "\",\"content\":\""
                + Base64.getEncoder().encodeToString(content) + "\"}";
    }
}
