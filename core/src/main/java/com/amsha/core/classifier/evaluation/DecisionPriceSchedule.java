package com.amsha.core.classifier.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A versioned schedule of decision-provider <b>inference</b> prices (M5).
 *
 * <p><b>It is deliberately not the global LLM price model.</b> {@code amsha.governance.price-per-1k-*}
 * prices the chat/completion path; those rates are not tied to the decision provider and model that
 * actually produced a decision, so reusing them would attribute one provider's cost to another. Each entry
 * here is keyed by the exact {@code providerId} and {@code modelId} the decision ran on, and carries the
 * rate's {@code source} and {@code effectiveDate} so a figure in the evidence can be traced to who
 * published it and when.
 *
 * <p><b>Scope is inference charges only.</b> A token rate captures what a hosted provider bills; it does
 * not capture the cost of running local compute. M5 is therefore defined over provider inference charges,
 * and the self-hosted local-compute method remains an open item — a lane that cannot be priced reports M5
 * as unpriced rather than as free.
 *
 * <p><b>Unknown cost fails closed.</b> {@link PriceEntry#costUsd} returns empty when a token count is
 * absent, and {@link #find} returns empty when no entry matches, so a caller can never turn missing
 * telemetry into a zero-cost decision. This is the opposite of a convenient default: M5's whole purpose is
 * to bound spend, and a silently-zero cost would defeat it.
 *
 * @param scheduleVersion the registered version this schedule is stored under; must be positive
 * @param description what the schedule is and where its rates came from
 * @param entries the priced provider/model pairs; empty means no rate has been authorised yet
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DecisionPriceSchedule(int scheduleVersion, String description, List<PriceEntry> entries) {

    /** Upper bound on entries, so a corrupt resource cannot turn pricing into unbounded work. */
    public static final int MAX_ENTRIES = 200;

    public DecisionPriceSchedule {
        if (scheduleVersion < 1) {
            throw new IllegalArgumentException("scheduleVersion must be >= 1, got " + scheduleVersion);
        }
        description = requireText(description, "description");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("price schedule " + scheduleVersion + " declares " + entries.size()
                    + " entries, above the " + MAX_ENTRIES + "-entry bound");
        }
        Set<String> keys = new HashSet<>();
        for (PriceEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!keys.add(entry.providerId() + "\u0000" + entry.modelId())) {
                throw new IllegalArgumentException("duplicate price entry for provider '" + entry.providerId()
                        + "' model '" + entry.modelId() + "'");
            }
        }
    }

    /**
     * The entry pricing an exact provider and model.
     *
     * <p>Matching is exact and requires both identifiers: a null or blank either way is unpriced rather
     * than a wildcard, so an unrecognised model cannot silently inherit a sibling's rate.
     *
     * @param providerId the provider slot the decision ran on
     * @param modelId the exact model id the decision ran on
     * @return the entry, or empty when this schedule does not price that pair
     */
    public Optional<PriceEntry> find(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry ->
                        entry.providerId().equals(providerId) && entry.modelId().equals(modelId))
                .findFirst();
    }

    /** The number of priced provider/model pairs. */
    public int entryCount() {
        return entries.size();
    }

    /** Whether this schedule prices nothing — the state until a rate is authorised. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Cost per 1,000 decisions from an accumulated priced total.
     *
     * <p>It is a ratio, so it is only meaningful over a positive decision count; a zero or negative count
     * yields empty rather than an undefined figure.
     *
     * @param decisions the number of decisions the total covers
     * @param totalPricedUsd the accumulated priced cost, in USD
     * @return the cost per 1,000 decisions, or empty when it cannot be computed
     */
    public static Optional<BigDecimal> costPerThousand(int decisions, BigDecimal totalPricedUsd) {
        if (decisions <= 0 || totalPricedUsd == null || totalPricedUsd.signum() < 0) {
            return Optional.empty();
        }
        return Optional.of(totalPricedUsd
                .multiply(BigDecimal.valueOf(1000))
                .divide(BigDecimal.valueOf(decisions), 12, RoundingMode.HALF_UP));
    }

    @Override
    public String toString() {
        return "DecisionPriceSchedule[version=" + scheduleVersion + ", entries=" + entries.size() + "]";
    }

    /**
     * One priced provider/model pair.
     *
     * @param providerId the provider slot identifier
     * @param modelId the exact model identifier
     * @param usdPer1kInputTokens USD per 1,000 input tokens
     * @param usdPer1kOutputTokens USD per 1,000 output tokens
     * @param source who published the rate (for traceability)
     * @param effectiveDate the ISO-8601 date the rate took effect
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PriceEntry(
            String providerId,
            String modelId,
            BigDecimal usdPer1kInputTokens,
            BigDecimal usdPer1kOutputTokens,
            String source,
            String effectiveDate) {

        public PriceEntry {
            providerId = requireText(providerId, "price entry providerId");
            modelId = requireText(modelId, "price entry modelId");
            usdPer1kInputTokens = requireRate(usdPer1kInputTokens, "price entry usdPer1kInputTokens");
            usdPer1kOutputTokens = requireRate(usdPer1kOutputTokens, "price entry usdPer1kOutputTokens");
            source = requireText(source, "price entry source");
            effectiveDate = requireText(effectiveDate, "price entry effectiveDate");
            try {
                LocalDate.parse(effectiveDate);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        "price entry effectiveDate is not an ISO-8601 date: " + effectiveDate, ex);
            }
        }

        /**
         * The priced cost of one decision.
         *
         * <p>Returns empty when either token count is absent: absent provider telemetry is <em>unknown</em>,
         * not zero, and a caller that treated it as zero would under-report spend.
         *
         * @param inputTokens the input token count reported by the provider, or null when unreported
         * @param outputTokens the output token count reported by the provider, or null when unreported
         * @return the cost in USD, or empty when it cannot be priced
         */
        public Optional<BigDecimal> costUsd(Long inputTokens, Long outputTokens) {
            if (inputTokens == null || outputTokens == null) {
                return Optional.empty();
            }
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("token counts must not be negative");
            }
            BigDecimal inputCost = usdPer1kInputTokens
                    .multiply(BigDecimal.valueOf(inputTokens))
                    .movePointLeft(3);
            BigDecimal outputCost = usdPer1kOutputTokens
                    .multiply(BigDecimal.valueOf(outputTokens))
                    .movePointLeft(3);
            return Optional.of(inputCost.add(outputCost));
        }
    }

    private static BigDecimal requireRate(BigDecimal rate, String field) {
        if (rate == null) {
            throw new IllegalArgumentException(field + " must be present; a missing rate must not be priced as free");
        }
        if (rate.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        if (rate.precision() > 20 || Math.abs((long) rate.scale()) > 12) {
            throw new IllegalArgumentException(field + " exceeds the supported precision or scale");
        }
        return rate;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
