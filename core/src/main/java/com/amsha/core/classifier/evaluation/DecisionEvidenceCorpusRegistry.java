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

import io.quarkus.runtime.Startup;

/**
 * The registered, versioned decision-evidence corpora.
 *
 * <p>Each corpus is a classpath resource under {@code decision-evidence/corpus-v&lt;n&gt;.json}, shipped
 * with the application rather than supplied by a caller — that is what makes a run reproducible and
 * keeps arbitrary state from being posted into an evidence evaluation. The registry is the only source
 * of a corpus, so "which version" is answered in exactly one place.
 *
 * <p><b>It fails closed and it does not fail startup.</b> A registered corpus that is missing, oversized,
 * or malformed is logged and left out of the registry, so the application still boots and the evidence
 * endpoint refuses with a clear reason instead of evaluating a partially-trusted corpus. The shipped
 * corpora are asserted valid by {@code DecisionEvidenceCorpusRegistryTest}; the tolerance here is for a
 * damaged deployment, not a licence to ship a bad corpus.
 *
 * <p>Loading is eager and the map is immutable afterwards, so a request never pays for parsing and two
 * concurrent readers cannot observe a half-loaded corpus.
 */
@Startup
@ApplicationScoped
public class DecisionEvidenceCorpusRegistry {

    private static final Logger log = Logger.getLogger(DecisionEvidenceCorpusRegistry.class);

    /** Directory holding the shipped corpora, relative to the classpath root. */
    public static final String RESOURCE_DIR = "decision-evidence";

    /** Default version selected by the evidence endpoint when its query parameter is omitted. */
    public static final String DEFAULT_VERSION = "2";

    /** The versions this build ships. A new corpus is a new entry here plus its resource. */
    static final int[] REGISTERED_VERSIONS = {1, 2};

    /** Upper bound on a corpus file, so a corrupt or hostile resource cannot exhaust memory. */
    static final int MAX_CORPUS_BYTES = 1_000_000;

    private final Map<Integer, DecisionEvidenceCorpus> corpora;

    @Inject
    public DecisionEvidenceCorpusRegistry(ObjectMapper mapper) {
        this.corpora = loadRegistered(strictCopy(Objects.requireNonNull(mapper, "mapper")));
    }

    /** Test seam: a registry over an explicit set of corpora, with no classpath access. */
    DecisionEvidenceCorpusRegistry(Map<Integer, DecisionEvidenceCorpus> corpora) {
        this.corpora = Collections.unmodifiableMap(new LinkedHashMap<>(corpora));
    }

    /** The corpus registered under {@code version}, or empty when this build does not ship it. */
    public Optional<DecisionEvidenceCorpus> find(int version) {
        return Optional.ofNullable(corpora.get(version));
    }

    /** The versions this registry actually loaded. */
    public Set<Integer> registeredVersions() {
        return corpora.keySet();
    }

    /** Whether any corpus loaded at all. */
    public boolean isEmpty() {
        return corpora.isEmpty();
    }

    private static Map<Integer, DecisionEvidenceCorpus> loadRegistered(ObjectMapper mapper) {
        Map<Integer, DecisionEvidenceCorpus> loaded = new LinkedHashMap<>();
        for (int version : REGISTERED_VERSIONS) {
            loadOne(mapper, version).ifPresent(corpus -> loaded.put(version, corpus));
        }
        if (loaded.isEmpty()) {
            log.warnf(
                    "No decision-evidence corpus could be loaded from %s/; the evidence endpoint will refuse until"
                            + " one is available",
                    RESOURCE_DIR);
        }
        return Collections.unmodifiableMap(loaded);
    }

    /**
     * A mapper that rejects unknown properties, independent of the application's global
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} config. Versioned metric inputs (the corpus and the price
     * schedule) must not silently discard a misspelled or unsupported field, because that changes what the
     * gates measure. A per-class {@code @JsonIgnoreProperties(ignoreUnknown=false)} does not override the
     * global lenient mapper in this Jackson version, so the strict setting comes from the mapper itself.
     */
    private static ObjectMapper strictCopy(ObjectMapper base) {
        return base.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    private static Optional<DecisionEvidenceCorpus> loadOne(ObjectMapper mapper, int version) {
        String path = RESOURCE_DIR + "/corpus-v" + version + ".json";
        byte[] bytes = readResource(path);
        if (bytes == null) {
            log.warnf("Decision-evidence corpus %s is not present on the classpath", path);
            return Optional.empty();
        }
        if (bytes.length > MAX_CORPUS_BYTES) {
            log.warnf(
                    "Decision-evidence corpus %s is %d bytes, above the %d-byte bound; refusing to load it",
                    path, bytes.length, MAX_CORPUS_BYTES);
            return Optional.empty();
        }
        try {
            DecisionEvidenceCorpus corpus = mapper.readValue(bytes, DecisionEvidenceCorpus.class);
            if (corpus.corpusVersion() != version) {
                log.warnf(
                        "Decision-evidence corpus %s declares version %d; refusing to register it as %d",
                        path, corpus.corpusVersion(), version);
                return Optional.empty();
            }
            log.infof(
                    "Decision-evidence corpus v%d loaded: %d cases across %d partitions",
                    version,
                    corpus.caseCount(),
                    corpus.cases().stream()
                            .map(DecisionEvidenceCase::partition)
                            .distinct()
                            .count());
            return Optional.of(corpus);
        } catch (IOException | IllegalArgumentException _) {
            // IllegalArgumentException covers the record's own validation. A shipped corpus failing it is a
            // build-time defect the unit test catches; at runtime we refuse the corpus rather than the app.
            log.warnf(
                    "Decision-evidence corpus %s failed validation or could not be parsed; refusing to load it", path);
            return Optional.empty();
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream stream = openResource(path)) {
            if (stream == null) {
                return null;
            }
            return stream.readAllBytes();
        } catch (IOException _) {
            log.warnf("Decision-evidence corpus %s could not be read; refusing to load it", path);
            return null;
        }
    }

    /**
     * Opens a shipped corpus, preferring the context classloader but not depending on it.
     *
     * <p>The context classloader can be present and still unable to see a resource this build ships: a
     * native image or a container loader need not delegate to the loader that owns the application
     * classes. Consulting it only when it is <em>null</em> therefore reports a shipped corpus as missing
     * and leaves the evidence endpoint refusing every request. Falling back to this class's own loader is
     * what makes the artifact reachable, and it is the loader the corpus is unambiguously registered
     * against.
     */
    private static InputStream openResource(String path) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            InputStream fromContextLoader = contextLoader.getResourceAsStream(path);
            if (fromContextLoader != null) {
                return fromContextLoader;
            }
        }
        return DecisionEvidenceCorpusRegistry.class.getResourceAsStream("/" + path);
    }
}
