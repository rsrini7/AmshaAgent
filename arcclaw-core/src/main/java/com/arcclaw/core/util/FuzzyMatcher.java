package com.arcclaw.core.util;

/**
 * Intelligent string matching utility using Damerau-Levenshtein Distance.
 * Useful for autocorrecting user typos in commands without hardcoding every combination.
 * Handles insertions, deletions, substitutions, and transpositions (swaps).
 */
public class FuzzyMatcher {

    /**
     * Checks if the input string is "close enough" to the target string.
     * The threshold is dynamic: 1 edit allowed for words <= 6 chars, 2 for longer ones.
     */
    public static boolean isClose(String input, String target) {
        if (input == null || target == null) return false;
        int dist = distance(input.toLowerCase().trim(), target.toLowerCase().trim());
        
        // Dynamic threshold logic: more forgiving for longer words
        if (target.length() <= 6) return dist <= 1; 
        return dist <= 2;
    }

    /**
     * Checks if the input matches target within a specific edit distance.
     */
    public static boolean isClose(String input, String target, int maxDistance) {
        if (input == null || target == null) return false;
        return distance(input.toLowerCase().trim(), target.toLowerCase().trim()) <= maxDistance;
    }

    /**
     * Damerau-Levenshtein Distance algorithm.
     * Measures edits: insertion, deletion, substitution, AND transposition of two adjacent characters.
     */
    public static int distance(String s1, String s2) {
        if (s1.equals(s2)) return 0;
        int n = s1.length();
        int m = s2.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[][] d = new int[n + 1][m + 1];

        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;

        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                
                // Transposition check (Damerau improvement)
                if (i > 1 && j > 1 && s1.charAt(i - 1) == s2.charAt(j - 2) && s1.charAt(i - 2) == s2.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + cost);
                }
            }
        }

        return d[n][m];
    }

    /**
     * Scans a sentence for any word that is fuzzy-close to the target.
     */
    public static boolean containsFuzzy(String sentence, String target) {
        if (sentence == null || target == null) return false;
        String[] words = sentence.split("\\s+");
        for (String word : words) {
            if (isClose(word, target)) return true;
        }
        return false;
    }
}
