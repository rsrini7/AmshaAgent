package com.arcclaw.core.util;

/**
 * Shared text pattern recognition and validation utilities.
 */
public final class TextPatternUtils {

    private static final int JSON_SALVAGE_MAX_LINES = 20;

    private TextPatternUtils() {}

    /**
     * Returns true if the original goal requested execution.
     */
    public static boolean goalRequestsExecution(String goal) {
        if (goal == null) return false;
        String lower = goal.toLowerCase();
        return lower.contains("execute")
                || lower.contains("must execute")
                || lower.contains("and execute")
                || lower.contains("fix and run")
                || lower.contains("run again")
                || lower.contains("run it")
                || lower.contains("run the")
                || lower.contains("share result")
                || lower.contains("share the result")
                || lower.contains("run same")
                || lower.contains("try same")
                || lower.contains("do same")
                || lower.contains("execute again")
                || lower.contains("share generated")
                || lower.contains("just do it");
                }
    /**
     * Extracts a normalized language name if mentioned in the goal.
     * Uses word boundaries to avoid false positives (e.g. "government" matching "go").
     */
    public static String goalMentionsLanguage(String goal) {
        if (goal == null) return null;
        String lower = " " + goal.toLowerCase().replaceAll("[^a-z0-9]", " ") + " ";
        
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
    public static String inferLanguage(String code) {
        if (code == null) return null;
        if (code.contains("public class") || code.contains("import java")) return "java";
        if (code.contains("def ") || code.contains("#!/usr/bin/env python")) return "python";
        if (code.contains("func ") && code.contains("package ")) return "go";
        if (code.contains("function ") || code.contains("const ") || code.contains("let ")) return "node";
        if (code.contains("#!/bin/bash") || code.contains("#!/bin/sh")) return "bash";
        return null;
    }

    /**
     * Returns true if the text appears to be a raw JSON object or array.
     * Includes a line-count guard to avoid false positives on multi-line prose
     * that happens to end with a closing brace.
     */
    public static boolean looksLikeJson(String text) {
        if (text == null) return false;
        String trimmed = text.strip();
        
        // Only flag single-block JSON — multi-line prose ending with } is valid.
        // 20 lines covers all realistic tool-call JSON echoes.
        long lineCount = trimmed.lines().count();
        if (lineCount > JSON_SALVAGE_MAX_LINES) return false;

        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }
}
