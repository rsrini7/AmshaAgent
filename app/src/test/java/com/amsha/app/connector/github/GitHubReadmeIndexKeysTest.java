package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GitHubReadmeIndexKeysTest {

    @Test
    void namespaceIsStableAndBoundToBothOwnerAndWorkspace() {
        String first = GitHubReadmeIndexKeys.userNamespace("owner@example.test", "workspace-one");

        assertEquals(first, GitHubReadmeIndexKeys.userNamespace("owner@example.test", "workspace-one"));
        assertNotEquals(first, GitHubReadmeIndexKeys.userNamespace("other@example.test", "workspace-one"));
        assertNotEquals(first, GitHubReadmeIndexKeys.userNamespace("owner@example.test", "workspace-two"));
        assertFalse(first.contains("owner"));
        assertFalse(first.contains("workspace"));
        assertTrue(first.matches("github-readme-user-[0-9a-f]{64}"));
    }

    @Test
    void documentKeyIsBoundToRepositoryAndRootPath() {
        String first = GitHubReadmeIndexKeys.documentId("github-readme-user-abc", 42L, "README.md");

        assertEquals(first, GitHubReadmeIndexKeys.documentId("github-readme-user-abc", 42L, "README.md"));
        assertNotEquals(first, GitHubReadmeIndexKeys.documentId("github-readme-user-abc", 43L, "README.md"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GitHubReadmeIndexKeys.documentId("github-readme-user-abc", 42L, "docs/README.md"));
    }
}
