package com.amsha.core.util;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Shared text pattern recognition and validation utilities.
 */
@ApplicationScoped
public class TextPatternUtils {

    @ConfigProperty(name = "amsha.core.util.json-salvage-max-lines", defaultValue = "20")
    int jsonSalvageMaxLines = 20;

    public TextPatternUtils() {
        /* CDI proxy */
    }

    /**
     * Returns true if the original goal requested execution.
     */
    public boolean goalRequestsExecution(String goal) {
        if (goal == null) return false;
        String lower = goal.toLowerCase(Locale.ROOT);
        return lower.contains("execute")
                || lower.contains("must execute")
                || lower.contains("and execute")
                || lower.contains("fix and run")
                || lower.contains("run again")
                || lower.contains("run it")
                || lower.contains("run the")
                || lower.contains("run java")
                || lower.contains("run python")
                || lower.contains("run node")
                || lower.contains("run go")
                || lower.contains("run bash")
                || lower.contains("share result")
                || lower.contains("share the result")
                || lower.contains("run same")
                || lower.contains("try same")
                || lower.contains("do same")
                || lower.contains("execute again")
                || lower.contains("share generated")
                || lower.contains("sandbox_exec")
                || lower.contains("run code")
                || lower.contains("run command")
                || lower.contains("run program")
                || lower.contains("just do it");
    }
    /**
     * Extracts a normalized language name if mentioned in the goal.
     * Uses word boundaries to avoid false positives (e.g. "government" matching "go").
     */
    public String goalMentionsLanguage(String goal) {
        if (goal == null) return null;
        String lower = " " + goal.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", " ") + " ";

        if (lower.contains(" java ")) return "java";
        if (lower.contains(" python ") || lower.contains(" py ")) return "python";
        if (lower.contains(" go ") || lower.contains(" golang ") || lower.contains(" go lang ")) return "go";
        if (lower.contains(" bash ") || lower.contains(" shell ")) return "bash";
        if (lower.contains(" node ") || lower.contains(" javascript ") || lower.contains(" js ")) return "node";
        return null;
    }

    /**
     * Infers the programming language from a code snippet if a specifier is missing.
     */
    public String inferLanguage(String code) {
        if (code == null) return null;
        if (code.contains("public class") || code.contains("import java")) return "java";
        if (code.contains("def ") || code.contains("#!/usr/bin/env python")) return "python";
        if (code.contains("func ") && code.contains("package ")) return "go";
        if (code.contains("function ") || code.contains("const ") || code.contains("let ")) return "node";
        if (code.contains("#!/bin/bash") || code.contains("#!/bin/sh")) return "bash";
        return null;
    }

    private static final Pattern FENCE_HEADER_PATTERN = Pattern.compile("^```([\\w-]*)\\R");

    /**
     * Returns true if the text appears to be a raw or fenced JSON object or array.
     * Includes checks for markdown fences (```json ... ```).
     */
    public boolean looksLikeJson(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            Matcher headerMatcher = FENCE_HEADER_PATTERN.matcher(trimmed);
            if (headerMatcher.find()) {
                String lang = headerMatcher.group(1).toLowerCase(Locale.ROOT);
                if (!lang.isEmpty() && !lang.equals("json") && !lang.equals("jsonc")) {
                    return false;
                }
            }
            trimmed = trimmed.replaceFirst("^```[\\w-]*\\R", "")
                    .replaceFirst("\\R```\\s*$", "")
                    .strip();
        }

        int maxLines = jsonSalvageMaxLines > 0 ? jsonSalvageMaxLines : 30;
        if (trimmed.lines().count() > maxLines) {
            return false;
        }

        boolean isObject = trimmed.startsWith("{")
                && trimmed.endsWith("}")
                && (trimmed.contains("\":") || trimmed.contains("\" :") || trimmed.contains("':"));
        boolean isArray = trimmed.equals("[]")
                || (trimmed.startsWith("[")
                        && trimmed.endsWith("]")
                        && (trimmed.startsWith("[{")
                                || trimmed.startsWith("[\"")
                                || (trimmed.contains("\"") && trimmed.contains(","))));
        return isObject || isArray;
    }
}
