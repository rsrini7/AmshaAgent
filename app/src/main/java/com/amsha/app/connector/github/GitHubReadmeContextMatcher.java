package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects bounded Markdown excerpts and labels each with its source path and commit. */
@ApplicationScoped
public class GitHubReadmeContextMatcher {
    private static final int MAX_EXCERPTS = 4;
    private static final int MAX_EXCERPT_CHARS = 700;
    private static final int MAX_CONTEXT_CHARS = 2400;
    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    Optional<String> match(List<SourceDocument> documents, String goal) {
        Set<String> queryWords = words(goal);
        if (queryWords.isEmpty()) {
            return Optional.empty();
        }
        List<ScoredExcerpt> candidates = new ArrayList<>();
        for (SourceDocument document : documents) {
            addExcerpts(candidates, document, queryWords);
        }
        return renderContext(candidates);
    }

    private void addExcerpts(List<ScoredExcerpt> candidates, SourceDocument document, Set<String> queryWords) {
        for (String paragraph : document.markdown().split("\\R\\s*\\R")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int score = score(trimmed, queryWords);
            if (score > 0) {
                candidates.add(new ScoredExcerpt(
                        score, clipped(trimmed, MAX_EXCERPT_CHARS), document.path(), document.commitSha()));
            }
        }
    }

    private Optional<String> renderContext(List<ScoredExcerpt> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort(Comparator.comparingInt(ScoredExcerpt::score).reversed());
        StringBuilder context = new StringBuilder();
        int count = Math.min(candidates.size(), MAX_EXCERPTS);
        for (int index = 0; index < count; index++) {
            ScoredExcerpt candidate = candidates.get(index);
            String sourceLabel = "Source: " + candidate.path() + " (commit " + candidate.commitSha() + ")\n";
            String excerpt = sourceLabel + candidate.text();
            int remaining = MAX_CONTEXT_CHARS - context.length();
            if (remaining <= sourceLabel.length()) {
                break;
            }
            if (excerpt.length() > remaining) {
                excerpt = sourceLabel + candidate.text().substring(0, remaining - sourceLabel.length());
            }
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append(excerpt);
        }
        return context.isEmpty() ? Optional.empty() : Optional.of(context.toString());
    }

    private static int score(String paragraph, Set<String> queryWords) {
        Set<String> paragraphWords = words(paragraph);
        int score = 0;
        for (String word : queryWords) {
            if (paragraphWords.contains(word)) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> words(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Matcher matcher = WORD_PATTERN.matcher(value.toLowerCase(Locale.ROOT));
        Set<String> words = new HashSet<>();
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static String clipped(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    record SourceDocument(String path, String commitSha, String markdown) {}

    private record ScoredExcerpt(int score, String text, String path, String commitSha) {}
}
