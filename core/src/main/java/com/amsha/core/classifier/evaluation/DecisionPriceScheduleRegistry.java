package com.amsha.core.classifier.evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * The registered, versioned decision price schedules (M5).
 *
 * <p>Each schedule is a classpath resource under {@code decision-evidence/price-schedule-v&lt;n&gt;.json},
 * shipped with the application rather than supplied by a caller: a price is reviewed data with a source and
 * an effective date, and letting a request supply rates would let it price its own spend. The registry is
 * the only source of a schedule, so "which version" is answered in exactly one place.
 *
 * <p><b>It fails closed and it does not fail startup</b>, exactly as {@link DecisionEvidenceCorpusRegistry}
 * does. A registered schedule that is missing, oversized, or malformed is logged and left out, so the
 * application still boots and the evidence runner reports M5 as unpriced rather than pricing against a
 * partially-trusted schedule. An <em>empty</em> schedule is valid and meaningful: it is the state before a
 * rate is authorised, and it prices nothing.
 */
@ApplicationScoped
public class DecisionPriceScheduleRegistry {

    private static final Logger log = Logger.getLogger(DecisionPriceScheduleRegistry.class);

    /** Directory holding the shipped schedules, relative to the classpath root. */
    public static final String RESOURCE_DIR = "decision-evidence";

    /** The versions this build ships. A new schedule is a new entry here plus its resource. */
    static final int[] REGISTERED_VERSIONS = {1};

    /** Upper bound on a schedule file, so a corrupt or hostile resource cannot exhaust memory. */
    static final int MAX_SCHEDULE_BYTES = 200_000;

    private final Map<Integer, DecisionPriceSchedule> schedules;

    @Inject
    public DecisionPriceScheduleRegistry(ObjectMapper mapper) {
        this.schedules = loadRegistered(strictCopy(Objects.requireNonNull(mapper, "mapper")));
    }

    /** Test seam: a registry over an explicit set of schedules, with no classpath access. */
    DecisionPriceScheduleRegistry(Map<Integer, DecisionPriceSchedule> schedules) {
        this.schedules = Collections.unmodifiableMap(new LinkedHashMap<>(schedules));
    }

    /** The schedule registered under {@code version}, or empty when this build does not ship it. */
    public Optional<DecisionPriceSchedule> find(int version) {
        return Optional.ofNullable(schedules.get(version));
    }

    /** The versions this registry actually loaded. */
    public Set<Integer> registeredVersions() {
        return schedules.keySet();
    }

    /** Whether any schedule loaded at all. */
    public boolean isEmpty() {
        return schedules.isEmpty();
    }

    private static Map<Integer, DecisionPriceSchedule> loadRegistered(ObjectMapper mapper) {
        Map<Integer, DecisionPriceSchedule> loaded = new LinkedHashMap<>();
        for (int version : REGISTERED_VERSIONS) {
            loadOne(mapper, version).ifPresent(schedule -> loaded.put(version, schedule));
        }
        if (loaded.isEmpty()) {
            log.warnf(
                    "No decision price schedule could be loaded from %s/; M5 will report every lane as unpriced",
                    RESOURCE_DIR);
        }
        return Collections.unmodifiableMap(loaded);
    }

    /**
     * A mapper that rejects unknown properties, independent of the application's global
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} config. A versioned price schedule must not silently drop a
     * misspelled field, because that can change an entry's key or rate and make M5 report the wrong cost.
     */
    private static ObjectMapper strictCopy(ObjectMapper base) {
        return base.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private static Optional<DecisionPriceSchedule> loadOne(ObjectMapper mapper, int version) {
        String path = RESOURCE_DIR + "/price-schedule-v" + version + ".json";
        byte[] bytes = readResource(path);
        if (bytes == null) {
            log.warnf("Decision price schedule %s is not present on the classpath", path);
            return Optional.empty();
        }
        if (bytes.length > MAX_SCHEDULE_BYTES) {
            log.warnf(
                    "Decision price schedule %s is %d bytes, above the %d-byte bound; refusing to load it",
                    path, bytes.length, MAX_SCHEDULE_BYTES);
            return Optional.empty();
        }
        try {
            DecisionPriceSchedule schedule = mapper.readValue(bytes, DecisionPriceSchedule.class);
            if (schedule.scheduleVersion() != version) {
                log.warnf(
                        "Decision price schedule %s declares version %d; refusing to register it as %d",
                        path, schedule.scheduleVersion(), version);
                return Optional.empty();
            }
            log.infof(
                    "Decision price schedule v%d loaded: %d priced provider/model pairs",
                    version, schedule.entryCount());
            return Optional.of(schedule);
        } catch (IOException | IllegalArgumentException _) {
            // IllegalArgumentException covers the record's own validation. A shipped schedule failing it is a
            // build-time defect the unit test catches; at runtime we refuse the schedule rather than the app.
            log.warnf("Decision price schedule %s failed validation or could not be parsed; refusing to load it", path);
            return Optional.empty();
        }
    }

    private static byte[] readResource(String path) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader != null
                ? loader.getResourceAsStream(path)
                : DecisionPriceScheduleRegistry.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                return null;
            }
            return stream.readAllBytes();
        } catch (IOException _) {
            log.warnf("Decision price schedule %s could not be read; refusing to load it", path);
            return null;
        }
    }
}
