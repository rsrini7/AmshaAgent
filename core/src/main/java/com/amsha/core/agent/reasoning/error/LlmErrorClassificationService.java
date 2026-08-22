package com.amsha.core.agent.reasoning.error;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.amsha.llm.api.error.LlmExceptionClassifier;
import com.amsha.llm.api.error.LlmProviderException;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

/**
 * Replaces the former hard-wired static {@code LlmExceptionClassifier} chain.
 * Discovers every {@link LlmExceptionClassifier} CDI bean (provider-specific
 * classifiers live in their provider modules; framework fallbacks stay in core)
 * and consults them in deterministic order: ascending {@link LlmExceptionClassifier#order()},
 * ties broken by bean class name — mirroring the repository CDI invariants used by
 * {@link CoreSpiResolutionValidator}-style resolution (getBeans → filter → sort → getReference).
 */
@Startup
@ApplicationScoped
public class LlmErrorClassificationService {

    private static final Logger LOG = Logger.getLogger(LlmErrorClassificationService.class);

    private final BeanManager beanManager;
    private volatile List<LlmExceptionClassifier> chain;

    private static volatile LlmErrorClassificationService shared;

    @Inject
    public LlmErrorClassificationService(BeanManager beanManager) {
        this.beanManager = beanManager;
        this.chain = List.of();
    }

    /** Test seam: builds the service over an explicit, pre-ordered classifier list. */
    public LlmErrorClassificationService(List<LlmExceptionClassifier> classifiers) {
        this.beanManager = null;
        this.chain = classifiers.stream()
                .sorted(Comparator.comparingInt(LlmExceptionClassifier::order)
                        .thenComparing(c -> c.getClass().getName()))
                .toList();
    }

    private static final List<LlmExceptionClassifier> CORE_LOCAL_FALLBACK =
            List.of(new LangChain4jExceptionClassifier(), new GenericExceptionClassifier());

    /**
     * Static gateway preserved from the former hard-wired utility so that non-CDI
     * constructs (the routing chat-model wrappers) keep working unchanged. Inside the
     * container this delegates to the CDI-built chain; outside it falls back to the
     * core-local framework classifiers (provider-specific classifiers live in their
     * own modules and are unavailable here by design). Already-normalized exceptions
     * always pass through untouched, matching the legacy utility.
     */
    public static LlmProviderException classify(Throwable t) {
        if (t instanceof LlmProviderException lpe) {
            return lpe;
        }
        LlmErrorClassificationService service = shared;
        if (service != null) {
            return service.doClassify(t);
        }
        for (LlmExceptionClassifier c : CORE_LOCAL_FALLBACK) {
            LlmProviderException r = c.classify(t);
            if (r != null) return r;
        }
        return null;
    }

    @PostConstruct
    void init() {
        if (beanManager == null || chain.size() > 0) {
            return;
        }
        Set<Bean<?>> allBeans = beanManager.getBeans(Object.class);
        if (allBeans == null || allBeans.isEmpty()) {
            LOG.warn("LlmErrorClassificationService: no CDI beans visible; classification disabled.");
            return;
        }
        List<LlmExceptionClassifier> discovered = allBeans.stream()
                .filter(b -> b != null && b.getBeanClass() != null)
                .filter(b -> LlmExceptionClassifier.class.isAssignableFrom(b.getBeanClass())
                        || (b.getTypes() != null && b.getTypes().contains(LlmExceptionClassifier.class)))
                .sorted(Comparator.comparing(b -> b.getBeanClass().getName()))
                .map(b -> {
                    CreationalContext<?> ctx = beanManager.createCreationalContext(b);
                    return (LlmExceptionClassifier) beanManager.getReference(b, LlmExceptionClassifier.class, ctx);
                })
                .sorted(Comparator.comparingInt(LlmExceptionClassifier::order)
                        .thenComparing(c -> c.getClass().getName()))
                .toList();
        // Ordered chains tolerate multiplicity by design (unlike singleton-port SPIs), but two
        // classifiers sharing an order value makes precedence name-dependent — surface it.
        Map<Integer, List<String>> byOrder = discovered.stream()
                .collect(Collectors.groupingBy(
                        LlmExceptionClassifier::order,
                        TreeMap::new,
                        Collectors.mapping(c -> c.getClass().getName(), Collectors.toList())));
        byOrder.forEach((order, names) -> {
            if (names.size() > 1) {
                LOG.warnf(
                        "LlmErrorClassificationService: %d classifiers share order %d (name-sorted): %s",
                        names.size(), order, names);
            }
        });
        chain = List.copyOf(discovered);
        shared = this;
        LOG.infof("LlmErrorClassificationService: %d classifier(s) registered.", chain.size());
    }

    /**
     * Instance-level classification: consults the ordered classifier chain and returns the
     * first recognized {@link LlmProviderException}, or {@code null} when nothing matches —
     * identical semantics to the previous static chain.
     */
    public LlmProviderException doClassify(Throwable t) {
        if (t instanceof LlmProviderException lpe) {
            return lpe;
        }
        for (LlmExceptionClassifier classifier : chain) {
            LlmProviderException classified = classifier.classify(t);
            if (classified != null) {
                return classified;
            }
        }
        return null;
    }

    /** @return the ordered, immutable classifier chain (exposed for diagnostics/tests). */
    public List<LlmExceptionClassifier> classifiers() {
        return chain;
    }

    /** Test seam: binds or unbinds the shared instance without a container. */
    public static void bindSharedForTest(LlmErrorClassificationService service) {
        shared = service;
    }
}
