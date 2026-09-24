package com.amsha.classifier.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic candidate reduction policy for closed choices.
 * Adding or removing options from a shortlist alters probability distributions;
 * therefore, candidate reduction rules and shortlists are versioned with the decision.
 *
 * @param eligibilityRule description of the deterministic code-side filtering rule
 * @param rationale architectural/safety rationale for the shortlist reduction
 * @param maxCandidates maximum allowed candidate count (at most 255)
 * @param admissibleCandidates explicit shortlist of allowed candidates, or empty if dynamically filtered
 */
public record CandidateReductionPolicy(
        String eligibilityRule, String rationale, int maxCandidates, List<String> admissibleCandidates) {

    public static final int DEFAULT_MAX_CANDIDATES = 255;

    public CandidateReductionPolicy {
        Objects.requireNonNull(eligibilityRule, "eligibilityRule");
        Objects.requireNonNull(rationale, "rationale");
        if (eligibilityRule.isBlank()) {
            throw new IllegalArgumentException("eligibilityRule must not be blank");
        }
        if (rationale.isBlank()) {
            throw new IllegalArgumentException("rationale must not be blank");
        }
        if (maxCandidates < 1 || maxCandidates > DEFAULT_MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "maxCandidates must be between 1 and " + DEFAULT_MAX_CANDIDATES + ", got " + maxCandidates);
        }
        if (admissibleCandidates == null) {
            admissibleCandidates = List.of();
        } else {
            List<String> copy = new ArrayList<>(admissibleCandidates.size());
            for (int i = 0; i < admissibleCandidates.size(); i++) {
                String candidate =
                        Objects.requireNonNull(admissibleCandidates.get(i), "admissibleCandidate[" + i + "]");
                if (candidate.isBlank()) {
                    throw new IllegalArgumentException("candidate at index " + i + " must not be blank");
                }
                copy.add(candidate);
            }
            if (copy.size() > maxCandidates) {
                throw new IllegalArgumentException("admissibleCandidates size (" + copy.size()
                        + ") exceeds maxCandidates (" + maxCandidates + ")");
            }
            admissibleCandidates = Collections.unmodifiableList(copy);
        }
    }

    public static CandidateReductionPolicy none() {
        return new CandidateReductionPolicy(
                "ALL_ADMISSIBLE", "No candidate reduction applied", DEFAULT_MAX_CANDIDATES, List.of());
    }

    /**
     * Filters candidate options according to the admissible candidate shortlist and maximum candidate count.
     *
     * @param candidates raw candidates to filter
     * @return unmodifiable list of eligible candidates
     */
    public List<String> filter(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>();
        for (String c : candidates) {
            if (c != null && !c.isBlank() && (admissibleCandidates.isEmpty() || admissibleCandidates.contains(c))) {
                filtered.add(c);
                if (filtered.size() >= maxCandidates) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(filtered);
    }
}
