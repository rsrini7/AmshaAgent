package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class GitHubReadmeConnectionSettingsParserTest {
    private static final String VALID_JSON =
            "" + "{\"repository\":\"rsrini7/AmshaAgent\"," + "\"allowedPaths\":[\"README.md\",\"docs/**/*.md\"]}";

    private final GitHubReadmeConnectionSettingsParser parser =
            new GitHubReadmeConnectionSettingsParser(new ObjectMapper());

    @Test
    void acceptsOnlyTheConfirmedFirstConnectionSettings() {
        GitHubReadmeConnectionSettings settings = parser.parse(VALID_JSON);

        assertEquals("rsrini7/AmshaAgent", settings.repository());
        assertEquals(GitHubReadmeSourcePolicy.FIRST_ALLOWED_PATHS, settings.allowedPaths());
    }

    @Test
    void rejectsMalformedUnknownDuplicateAndWidenedSettings() {
        assertRejected("not-json");
        assertRejected(VALID_JSON.substring(0, VALID_JSON.length() - 1) + ",\"branch\":\"main\"}");
        assertRejected("{\"repository\":\"rsrini7/AmshaAgent\",\"repository\":\"attacker/repo\","
                + "\"allowedPaths\":[\"README.md\",\"docs/**/*.md\"]}");
        assertRejected("{\"repository\":\"attacker/repo\"," + "\"allowedPaths\":[\"README.md\",\"docs/**/*.md\"]}");
        assertRejected("{\"repository\":\"rsrini7/AmshaAgent\"," + "\"allowedPaths\":[\"README.md\",\"docs/**\"]}");
        assertRejected("{\"repository\":\"rsrini7/AmshaAgent\","
                + "\"allowedPaths\":[\"README.md\",\"README.md\",\"docs/**/*.md\"]}");
        assertRejected("{\"repository\":\"rsrini7/AmshaAgent\",\"allowedPaths\":\"README.md\"}");
        assertRejected(VALID_JSON + " {}");
    }

    @Test
    void rejectsInvalidUtf8AtTheRestBoundary() {
        byte[] invalidUtf8 = {(byte) '{', (byte) 0xc3, (byte) 0x28, (byte) '}'};
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new ByteArrayInputStream(invalidUtf8)));
    }

    @Test
    void permitsOnlyMarkdownFilesWithinTheTwoConfirmedPathPatterns() {
        GitHubReadmeConnectionSettings settings = GitHubReadmeSourcePolicy.firstConnection();

        assertTrue(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "README.md"));
        assertTrue(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/guide.md"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/architecture/adr.markdown"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "src/Main.java"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/config.yaml"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/.private.md"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/generated/api.md"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/build/api.md"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/../secrets.md"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs\\guide.md"));
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "other/guide.md"));
    }

    @Test
    void rejectsTheMarkdownExtensionAliasAndWidenedPatterns() {
        GitHubReadmeConnectionSettings settings = GitHubReadmeSourcePolicy.firstConnection();
        assertFalse(GitHubReadmeSourcePolicy.isAllowedMarkdownPath(settings, "docs/architecture/adr.markdown"));
        assertRejected("{\"repository\":\"rsrini7/AmshaAgent\","
                + "\"allowedPaths\":[\"README.md\",\"docs/**/*.md\",\"docs/**/*.markdown\"]}");
    }

    private void assertRejected(String json) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(json));
    }
}
