package com.amsha.core.classifier.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Contract for the M5 price schedule and its registry.
 *
 * <p>The load-bearing property is that <b>unknown cost never becomes zero</b>: an unpriced provider/model
 * or an absent token count must surface as empty, because M5 exists to bound spend and a silently-zero
 * figure would defeat it. The happy path asserts against the schedule this build actually ships, so a
 * damaged resource fails here rather than at runtime.
 */
class DecisionPriceScheduleRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void refusesUnknownScheduleFieldsEvenWhenInjectedMapperIsLenient() throws IOException {
        String resourcePath = DecisionPriceScheduleRegistry.RESOURCE_DIR + "/price-schedule-v1.json";
        byte[] resource = DecisionRegistryTestSupport.resourceWithUnknownField(resourcePath, "unexpectedScheduleField");

        DecisionPriceScheduleRegistry registry = DecisionRegistryTestSupport.withResource(
                resourcePath,
                resource,
                () -> new DecisionPriceScheduleRegistry(DecisionRegistryTestSupport.lenientMapper()));

        assertTrue(registry.find(1).isEmpty(), "a schedule with an unsupported field must not be registered");
    }

    @Test
    void loadsTheShippedScheduleWithOnlyTheApprovedOmlxZeroRate() {
        DecisionPriceScheduleRegistry registry = new DecisionPriceScheduleRegistry(MAPPER);

        assertTrue(registry.registeredVersions().contains(1), "schedule v1 must be registered");
        DecisionPriceSchedule schedule = registry.find(1).orElseThrow();
        assertEquals(1, schedule.scheduleVersion());
        assertEquals(1, schedule.entryCount());
        DecisionPriceSchedule.PriceEntry entry =
                schedule.find("omlx", "clef-flash-oQ4e").orElseThrow();
        assertEquals(0, entry.usdPer1kInputTokens().signum());
        assertEquals(0, entry.usdPer1kOutputTokens().signum());
        assertFalse(entry.source().isBlank());
        assertFalse(entry.effectiveDate().isBlank());
        assertTrue(schedule.find("typesafe", "jev-1.13.0").isEmpty());
        assertFalse(schedule.description().isBlank(), "the schedule must state its scope");
    }

    @Test
    void anUnshippedVersionIsAbsentRatherThanInvented() {
        DecisionPriceScheduleRegistry registry = new DecisionPriceScheduleRegistry(MAPPER);

        assertTrue(registry.find(99).isEmpty(), "a version this build does not ship must be absent");
    }

    @Test
    void findsTheShippedScheduleEvenWhenTheContextClassLoaderCannot() {
        // A native image or container thread can carry a context classloader that is present yet blind to a
        // resource this build ships. The registry must fall back to its own classloader rather than report
        // the schedule as missing, which would price every lane as unpriced.
        String resourcePath = DecisionPriceScheduleRegistry.RESOURCE_DIR + "/price-schedule-v1.json";

        DecisionPriceScheduleRegistry registry = DecisionRegistryTestSupport.withResourceHidden(
                resourcePath, () -> new DecisionPriceScheduleRegistry(MAPPER));

        assertTrue(
                registry.registeredVersions().contains(1),
                "a context classloader that misses must not hide a schedule the registry itself ships");
    }

    @Test
    void pricesAnExactProviderAndModelOnly() {
        DecisionPriceSchedule schedule = scheduleWith(pricedEntry());

        assertTrue(schedule.find("typesafe", "jev-1.13.0").isPresent(), "the priced pair resolves");
        assertTrue(schedule.find("typesafe", "jev-1.14.0").isEmpty(), "a different model is unpriced");
        assertTrue(schedule.find("other", "jev-1.13.0").isEmpty(), "a different provider is unpriced");
        assertTrue(schedule.find(null, "jev-1.13.0").isEmpty(), "a null model is unpriced, never a wildcard");
        assertTrue(schedule.find("typesafe", null).isEmpty(), "a null provider is unpriced");
        assertTrue(schedule.find("  ", "jev-1.13.0").isEmpty(), "a blank provider is unpriced");
    }

    @Test
    void costFailsClosedWhenATokenCountIsAbsent() {
        DecisionPriceSchedule.PriceEntry entry = pricedEntry();

        assertEquals(
                0,
                entry.costUsd(2000L, 1000L).orElseThrow().compareTo(new BigDecimal("0.021")),
                "2000 in + 1000 out at 0.003/0.015");
        assertTrue(entry.costUsd(null, 1000L).isEmpty(), "absent input tokens are unknown, not zero");
        assertTrue(entry.costUsd(2000L, null).isEmpty(), "absent output tokens are unknown, not zero");
        assertTrue(entry.costUsd(null, null).isEmpty(), "fully absent telemetry is unknown");
    }

    @Test
    void costPerThousandIsARatioAndUndefinedWithoutDecisions() {
        assertEquals(
                0,
                DecisionPriceSchedule.costPerThousand(500, new BigDecimal("10.5"))
                        .orElseThrow()
                        .compareTo(new BigDecimal("21")));
        assertTrue(
                DecisionPriceSchedule.costPerThousand(0, new BigDecimal("10.5")).isEmpty());
        assertTrue(DecisionPriceSchedule.costPerThousand(-1, new BigDecimal("10.5"))
                .isEmpty());
        assertTrue(
                DecisionPriceSchedule.costPerThousand(500, new BigDecimal("-1")).isEmpty());
    }

    @Test
    void refusesADuplicateProviderModelPair() {
        DecisionPriceSchedule.PriceEntry entry = pricedEntry();

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionPriceSchedule(1, "description", List.of(entry, entry)));
        assertTrue(thrown.getMessage().contains("duplicate"), thrown.getMessage());
    }

    @Test
    void refusesAnEntryWithoutASourceOrAValidEffectiveDate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionPriceSchedule.PriceEntry(
                        "typesafe",
                        "jev-1.13.0",
                        new BigDecimal("0.003"),
                        new BigDecimal("0.015"),
                        "  ",
                        "2026-10-01"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionPriceSchedule.PriceEntry(
                        "typesafe",
                        "jev-1.13.0",
                        new BigDecimal("0.003"),
                        new BigDecimal("0.015"),
                        "typesafe.ai/pricing",
                        "not-a-date"));
    }

    @Test
    void refusesNegativeOrUnsupportedPrecisionRates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionPriceSchedule.PriceEntry(
                        "typesafe",
                        "jev-1.13.0",
                        new BigDecimal("-0.001"),
                        new BigDecimal("0.015"),
                        "typesafe.ai/pricing",
                        "2026-10-01"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionPriceSchedule.PriceEntry(
                        "typesafe",
                        "jev-1.13.0",
                        new BigDecimal("1e-13"),
                        new BigDecimal("0.015"),
                        "typesafe.ai/pricing",
                        "2026-10-01"));
    }

    @Test
    void refusesAnEntryWithAMissingRateRatherThanPricingItFree() {
        // A missing JSON rate deserialises to null; the record must refuse it instead of reading it as 0.0, which
        // would let M5 report a non-zero spend as free.
        assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionPriceSchedule.PriceEntry(
                        "typesafe", "jev-1.13.0", null, new BigDecimal("0.015"), "typesafe.ai/pricing", "2026-10-01"));

        String entryMissingInputRate = "{\"providerId\":\"typesafe\",\"modelId\":\"jev-1.13.0\","
                + "\"usdPer1kOutputTokens\":0.015,\"source\":\"typesafe.ai/pricing\",\"effectiveDate\":\"2026-10-01\"}";
        assertThrows(
                JsonMappingException.class,
                () -> MAPPER.readValue(entryMissingInputRate, DecisionPriceSchedule.PriceEntry.class));
    }

    @Test
    void anEmptyScheduleIsValidAndPricesNothing() {
        DecisionPriceSchedule schedule = new DecisionPriceSchedule(1, "no rates authorised yet", List.of());

        assertTrue(schedule.isEmpty());
        assertTrue(schedule.find("typesafe", "jev-1.13.0").isEmpty());
    }

    private static DecisionPriceSchedule.PriceEntry pricedEntry() {
        return new DecisionPriceSchedule.PriceEntry(
                "typesafe",
                "jev-1.13.0",
                new BigDecimal("0.003"),
                new BigDecimal("0.015"),
                "typesafe.ai/pricing",
                "2026-10-01");
    }

    private static DecisionPriceSchedule scheduleWith(DecisionPriceSchedule.PriceEntry entry) {
        return new DecisionPriceSchedule(1, "test schedule", List.of(entry));
    }
}
