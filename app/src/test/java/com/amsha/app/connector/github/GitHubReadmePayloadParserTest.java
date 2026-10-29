package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class GitHubReadmePayloadParserTest {

    private static final GitHubReadmePayloadParser PARSER = new GitHubReadmePayloadParser();
    private static final String REPOSITORY = "rsrini7/rsrini7";
    private static final String MARKDOWN = "# Public profile\n\nSearchable fixture text.";

    @Test
    void parsesSelectedInstallationAndReadOnlyRepositoryToken() {
        var installation = PARSER.parseInstallation("""
                {"id":481516,"repository_selection":"selected","permissions":{"contents":"read","metadata":"read"}}
                """);
        var token = PARSER.parseToken("""
                {"token":"test-token-placeholder","permissions":{"contents":"read","metadata":"read"},
                 "repositories":[{"id":42,"full_name":"rsrini7/rsrini7"}]}
                """, REPOSITORY);

        assertEquals(481516L, installation.id());
        assertEquals("test-token-placeholder", token.value());
        assertEquals(42L, token.repositoryId());
    }

    @Test
    void acceptsOnlyAMetadataOnlyInstallationTokenAndAnInstallationContainingThisRepositoryAlone() {
        var token = PARSER.parseMetadataToken("""
                {"token":"metadata-fixture","permissions":{"metadata":"read"}}
                """);
        assertEquals("metadata-fixture", token.value());

        var repository = PARSER.parseInstalledRepositories("""
                {"total_count":1,"repositories":[{"id":42,"full_name":"rsrini7/rsrini7"}]}
                """, REPOSITORY);
        assertEquals(42L, repository.id());
    }

    @Test
    void rejectsBroaderInstallationScopeAndMetadataTokensWithContentsAccess() {
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseMetadataToken("""
                        {"token":"metadata-fixture","permissions":{"metadata":"read","contents":"read"}}
                        """));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseInstalledRepositories("""
                        {"total_count":2,"repositories":[{"id":42,"full_name":"rsrini7/rsrini7"}]}
                        """, REPOSITORY));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseInstalledRepositories("""
                        {"total_count":1,"repositories":[{"id":42,"full_name":"other/repository"}]}
                        """, REPOSITORY));
    }

    @Test
    void rejectsInstallationWriteOrUnrelatedPermissions() {
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseInstallation("""
                        {"id":7,"repository_selection":"selected","permissions":{"contents":"write","metadata":"read"}}
                        """));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseInstallation("""
                        {"id":7,"repository_selection":"selected","permissions":{"contents":"read","issues":"read"}}
                        """));
    }

    @Test
    void parsesPublicRepositoryDefaultBranchAndRootReadme() {
        var repository = PARSER.parseRepository("""
                {"id":42,"full_name":"rsrini7/rsrini7","private":false,"default_branch":"profile/main"}
                """, REPOSITORY);
        var readme = PARSER.parseReadme(readmeJson("README.md", "file", MARKDOWN), 4096);

        assertEquals(42L, repository.id());
        assertEquals("profile/main", repository.defaultBranch());
        assertEquals(MARKDOWN, readme.markdown());
    }

    @Test
    void rejectsUnexpectedInstallationAndRepositoryScope() {
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseInstallation("""
                {"id":7,"repository_selection":"all"}
                """));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseToken("""
                {"token":"placeholder","permissions":{"contents":"write"},
                 "repositories":[{"id":42,"full_name":"rsrini7/rsrini7"}]}
                """, REPOSITORY));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseToken("""
                {"token":"placeholder","permissions":{"contents":"read"},
                 "repositories":[{"id":42,"full_name":"rsrini7/rsrini7"},
                                  {"id":43,"full_name":"someone/else"}]}
                """, REPOSITORY));
    }

    @Test
    void rejectsPrivateOrMismatchedRepositoriesAndUnexpectedReadmePath() {
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseRepository("""
                {"id":42,"full_name":"rsrini7/rsrini7","private":true,"default_branch":"main"}
                """, REPOSITORY));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseRepository("""
                {"id":42,"full_name":"someone/else","private":false,"default_branch":"main"}
                """, REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> PARSER.parseReadme(readmeJson("docs/README.md", "file", MARKDOWN), 4096));
    }

    @Test
    void rejectsMalformedTypesDuplicatesInvalidBase64AndOversizedContent() {
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseRepository("""
                {"id":"42","full_name":"rsrini7/rsrini7","private":false,"default_branch":"main"}
                """, REPOSITORY));
        assertThrows(IllegalArgumentException.class, () -> PARSER.parseRepository("""
                {"id":42,"id":43,"full_name":"rsrini7/rsrini7","private":false,"default_branch":"main"}
                """, REPOSITORY));
        assertThrows(
                IllegalArgumentException.class,
                () -> PARSER.parseReadme(
                        "{\"path\":\"README.md\",\"type\":\"file\",\"encoding\":\"base64\",\"size\":3,"
                                + "\"sha\":\"0123456789012345678901234567890123456789\",\"content\":\"%%%\"}",
                        4096));
        assertThrows(
                IllegalArgumentException.class, () -> PARSER.parseReadme(readmeJson("README.md", "file", MARKDOWN), 8));
    }

    private static String readmeJson(String path, String type, String markdown) {
        String encoded = Base64.getEncoder().encodeToString(markdown.getBytes(StandardCharsets.UTF_8));
        return "{\"path\":\"" + path + "\",\"type\":\"" + type + "\",\"encoding\":\"base64\",\"size\":"
                + markdown.getBytes(StandardCharsets.UTF_8).length
                + ",\"sha\":\"0123456789012345678901234567890123456789\",\"content\":\""
                + encoded + "\"}";
    }
}
