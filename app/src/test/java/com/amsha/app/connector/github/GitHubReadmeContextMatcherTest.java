package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubReadmeContextMatcherTest {

    private GitHubReadmeContextMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new GitHubReadmeContextMatcher();
    }

    @Test
    void matchesChannelSpiDocumentationWithHighPriority() {
        GitHubReadmeContextMatcher.SourceDocument channelDoc =
                new GitHubReadmeContextMatcher.SourceDocument("docs/CHANNEL_SPI.md", "0fc0ae1", """
                # Channel SPI Documentation

                Adding a new channel to Amsha Agent requires zero changes to the core engine. You just need to create a new module that implements the 6-interface contract.

                ## The 6-Interface Contract
                All interfaces live in amsha-channel-api.
                """);

        GitHubReadmeContextMatcher.SourceDocument unrelatedDoc = new GitHubReadmeContextMatcher.SourceDocument(
                "docs/adr/architecture/ADR-extract-subsystems.md", "0fc0ae1", """
                ### 7. amsha-security implementation
                Explain repository architecture details for security. This repository uses modular architecture.
                """);

        List<GitHubReadmeContextMatcher.SourceDocument> docs = List.of(unrelatedDoc, channelDoc);

        Optional<String> matched = matcher.match(docs, "Explain the Channel SPI architecture in this repository");

        assertTrue(matched.isPresent());
        String context = matched.get();
        assertTrue(context.contains("docs/CHANNEL_SPI.md"));
        assertTrue(context.contains("Channel SPI Documentation"));
    }

    @Test
    void filtersStopWordsAndFindsMemoryArchitecture() {
        GitHubReadmeContextMatcher.SourceDocument memoryDoc = new GitHubReadmeContextMatcher.SourceDocument(
                "docs/adr/architecture/ADR-LangChain4j.md", "0fc0ae1", """
                # Memory and Retrieval Architecture

                Amsha agent memory architecture uses tiered memory: core memory, recall memory, and archival memory.
                """);

        GitHubReadmeContextMatcher.SourceDocument generalDoc =
                new GitHubReadmeContextMatcher.SourceDocument("docs/GENERAL.md", "0fc0ae1", """
                # General Overview

                What does amsha agent do in this repository? It runs workflows.
                """);

        List<GitHubReadmeContextMatcher.SourceDocument> docs = List.of(generalDoc, memoryDoc);

        Optional<String> matched = matcher.match(docs, "what amsha agent memory architecture does ?");

        assertTrue(matched.isPresent());
        String context = matched.get();
        assertTrue(context.contains("docs/adr/architecture/ADR-LangChain4j.md"));
        assertTrue(context.contains("Memory and Retrieval Architecture"));
    }

    @Test
    void fallsBackToReadmeWhenNoQueryWordsMatch() {
        GitHubReadmeContextMatcher.SourceDocument readme =
                new GitHubReadmeContextMatcher.SourceDocument("README.md", "0fc0ae1", """
                # Amsha Agent

                Amsha is a high-performance agent runtime.
                """);

        List<GitHubReadmeContextMatcher.SourceDocument> docs = List.of(readme);

        Optional<String> matched = matcher.match(docs, "unrelatedfoobarbaz");

        assertTrue(matched.isPresent());
        assertTrue(matched.get().contains("Source: README.md"));
        assertTrue(matched.get().contains("Amsha Agent"));
    }

    @Test
    void returnsEmptyWhenDocumentsEmptyOrGoalEmpty() {
        assertFalse(matcher.match(List.of(), "Explain memory").isPresent());
        assertFalse(matcher.match(null, "Explain memory").isPresent());
        assertFalse(
                matcher.match(List.of(new GitHubReadmeContextMatcher.SourceDocument("README.md", "1", "content")), "")
                        .isPresent());
        assertFalse(matcher.match(
                        List.of(new GitHubReadmeContextMatcher.SourceDocument("README.md", "1", "content")), "   ")
                .isPresent());
    }
}
