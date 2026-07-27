package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class GroupAnalyticsServiceTest {

    @Test
    void aggregatesConsultationOutcomesAndFormatsSummary() {
        GroupAnalyticsService service = new GroupAnalyticsService();
        assertEquals("No group consultations recorded yet.", service.summary());
        assertEquals(0, service.successRate("missing"));
        assertEquals(0, service.avgMembersConsulted("missing"));

        service.recordOutcome("kailasa", List.of("security", "logic"), 3, 0, true);
        service.recordOutcome("kailasa", List.of("logic", "security"), 1, 2, false);
        service.recordOutcome("other", List.of("docs"), 2, 0, true);

        assertAll(
                () -> assertEquals(3, service.totalConsultations()),
                () -> assertTrue(service.trackedGroups().containsAll(List.of("kailasa", "other"))),
                () -> assertEquals(0.5, service.successRate("kailasa")),
                () -> assertEquals(2.0, service.avgMembersConsulted("kailasa")),
                () -> assertEquals(
                        "logic+security",
                        service.topDomainCombinations(1).getFirst().combo()),
                () -> assertEquals(
                        0.5, service.topDomainCombinations(1).getFirst().successRate()),
                () -> assertTrue(service.summary().contains("GROUP CONSULTATION ANALYTICS")));
    }

    @Test
    void disabledAnalyticsDoesNotRecordAndZeroStatsAreSafe() {
        GroupAnalyticsService service = new GroupAnalyticsService();
        service.analyticsEnabled = false;
        service.recordOutcome("ignored", List.of("x"), 1, 0, true);
        assertEquals(0, service.totalConsultations());
        assertEquals(0, new GroupAnalyticsService.DomainComboStats("none", 0, 0).successRate());
    }
}
