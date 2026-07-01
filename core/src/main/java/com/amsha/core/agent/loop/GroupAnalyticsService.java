package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tracks group consultation outcomes and provides analytics on which
 * group compositions work best for which task types.
 *
 * <h3>Metrics Tracked:</h3>
 * <ul>
 *   <li>Success rate per group (members produced useful results)</li>
 *   <li>Average members consulted per group</li>
 *   <li>Most common domain combinations</li>
 *   <li>Failure rate per member role</li>
 * </ul>
 *
 * <h3>Storage:</h3>
 * In-memory ConcurrentHashMap. No persistence — metrics reset on restart.
 * For persistent analytics, the audit event trail (eventDetail JSON) is the
 * source of truth. This service provides real-time aggregation.
 */
@ApplicationScoped
public class GroupAnalyticsService {

    private static final Logger log = Logger.getLogger(GroupAnalyticsService.class);

    @ConfigProperty(name = "amsha.group.analytics.enabled", defaultValue = "true")
    boolean analyticsEnabled = true;

    /** Group ID → list of consultation records. */
    private final ConcurrentHashMap<String, List<ConsultationRecord>> records = new ConcurrentHashMap<>();

    /**
     * Records the outcome of a group consultation.
     *
     * @param groupId          The group that was consulted
     * @param domains          The task domains (from classifier)
     * @param membersConsulted Number of members that provided useful results
     * @param membersFailed    Number of members that failed or timed out
     * @param success          Whether the consultation produced useful results
     */
    public void recordOutcome(
            String groupId, List<String> domains, int membersConsulted, int membersFailed, boolean success) {

        if (!analyticsEnabled) {
            return;
        }

        ConsultationRecord record =
                new ConsultationRecord(Instant.now(), groupId, domains, membersConsulted, membersFailed, success);

        records.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>()).add(record);

        log.debugf(
                "[ANALYTICS] Recorded: group=%s domains=%s consulted=%d failed=%b success=%b",
                groupId, domains, membersConsulted, membersFailed, success);
    }

    /**
     * Returns success rate for a specific group.
     *
     * @param groupId The group ID
     * @return Success rate (0.0 to 1.0), or 0.0 if no records
     */
    public double successRate(String groupId) {
        List<ConsultationRecord> groupRecords = records.get(groupId);
        if (groupRecords == null || groupRecords.isEmpty()) {
            return 0.0;
        }
        long successes = groupRecords.stream().filter(r -> r.success).count();
        return (double) successes / groupRecords.size();
    }

    /**
     * Returns average members consulted for a specific group.
     */
    public double avgMembersConsulted(String groupId) {
        List<ConsultationRecord> groupRecords = records.get(groupId);
        if (groupRecords == null || groupRecords.isEmpty()) {
            return 0.0;
        }
        return groupRecords.stream().mapToInt(r -> r.membersConsulted).average().orElse(0.0);
    }

    /**
     * Returns the most common domain combinations across all consultations.
     *
     * @param limit Maximum number of entries to return
     * @return List of domain combinations sorted by frequency (descending)
     */
    public List<DomainComboStats> topDomainCombinations(int limit) {
        Map<String, Integer> comboCounts = new HashMap<>();
        Map<String, Integer> comboSuccesses = new HashMap<>();

        for (List<ConsultationRecord> groupRecords : records.values()) {
            for (ConsultationRecord record : groupRecords) {
                String combo = String.join("+", record.domains.stream().sorted().toList());
                comboCounts.merge(combo, 1, Integer::sum);
                if (record.success) {
                    comboSuccesses.merge(combo, 1, Integer::sum);
                }
            }
        }

        return comboCounts.entrySet().stream()
                .map(e -> new DomainComboStats(e.getKey(), e.getValue(), comboSuccesses.getOrDefault(e.getKey(), 0)))
                .sorted(Comparator.comparingInt(DomainComboStats::totalConsultations)
                        .reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Returns all group IDs that have been consulted.
     */
    public List<String> trackedGroups() {
        return new ArrayList<>(records.keySet());
    }

    /**
     * Returns total consultation count across all groups.
     */
    public int totalConsultations() {
        return records.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Returns a formatted summary of all analytics.
     */
    public String summary() {
        if (records.isEmpty()) {
            return "No group consultations recorded yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== GROUP CONSULTATION ANALYTICS ===\n\n");

        for (String groupId : trackedGroups()) {
            sb.append(String.format(Locale.ROOT, "Group: %s\n", groupId));
            sb.append(String.format(
                    Locale.ROOT, "  Consultations: %d\n", records.get(groupId).size()));
            sb.append(String.format(Locale.ROOT, "  Success rate: %.1f%%\n", successRate(groupId) * 100));
            sb.append(String.format(Locale.ROOT, "  Avg members consulted: %.1f\n", avgMembersConsulted(groupId)));
        }

        sb.append("\nTop domain combinations:\n");
        for (DomainComboStats combo : topDomainCombinations(5)) {
            sb.append(String.format(
                    Locale.ROOT,
                    "  %s: %d consultations (%.1f%% success)\n",
                    combo.combo(),
                    combo.totalConsultations(),
                    combo.successRate() * 100));
        }

        return sb.toString();
    }

    // ── Records ────────────────────────────────────────────────────────────

    private record ConsultationRecord(
            Instant timestamp,
            String groupId,
            List<String> domains,
            int membersConsulted,
            int membersFailed,
            boolean success) {}

    /**
     * Statistics for a domain combination.
     */
    public record DomainComboStats(String combo, int totalConsultations, int successes) {
        public double successRate() {
            return totalConsultations == 0 ? 0.0 : (double) successes / totalConsultations;
        }
    }
}
