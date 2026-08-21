package com.amsha.core.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.tools.search.ToolSearchPort;

import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;

/**
 * Validates SPI bean cardinality at application startup and provides the canonical
 * resolution algorithm conforming to repository CDI invariants:
 * BeanManager.getBeans(Object.class) -> filter by SPI type -> sort provider names -> getReference().
 */
@Startup
@ApplicationScoped
public class CoreSpiResolutionValidator {

    private static final Logger LOG = Logger.getLogger(CoreSpiResolutionValidator.class);

    @Inject
    BeanManager beanManager;

    @PostConstruct
    void validate() {
        validateCardinality(SandboxExecutionPort.class);
        validateCardinality(ToolSearchPort.class);
        LOG.info("CoreSpiResolutionValidator: SPI resolution validation completed successfully.");
    }

    public <T> void validateCardinality(Class<T> spiClass) {
        resolveOptional(beanManager, spiClass);
        LOG.debugf("CoreSpiResolutionValidator: SPI %s validated successfully.", spiClass.getSimpleName());
    }

    /**
     * Discovers an optional SPI provider using BeanManager.getBeans(Object.class)
     * followed by type filtering, name sorting, and getReference().
     *
     * @param beanManager the CDI BeanManager
     * @param spiClass    the SPI interface class
     * @return Optional containing the resolved implementation, or Optional.empty() if no provider is present
     * @throws IllegalStateException if multiple providers are discovered
     */
    public static <T> Optional<T> resolveOptional(BeanManager beanManager, Class<T> spiClass) {
        if (beanManager == null || spiClass == null) {
            return Optional.empty();
        }

        Set<Bean<?>> allBeans = beanManager.getBeans(Object.class);
        if (allBeans == null || allBeans.isEmpty()) {
            return Optional.empty();
        }

        List<Bean<?>> matchingBeans = allBeans.stream()
                .filter(b -> b != null && b.getBeanClass() != null)
                .filter(b -> spiClass.isAssignableFrom(b.getBeanClass())
                        || (b.getTypes() != null && b.getTypes().contains(spiClass)))
                .sorted(Comparator.comparing(b -> b.getBeanClass().getName()))
                .toList();

        if (matchingBeans.isEmpty()) {
            return Optional.empty();
        }

        Set<Bean<?>> candidates = new HashSet<>(matchingBeans);
        Bean<?> selectedBean;
        try {
            // Always let CDI select the active bean, including the single-candidate case.
            // This is what excludes disabled and shadow-only providers.
            selectedBean = beanManager.resolve(candidates);
        } catch (Exception _) {
            selectedBean = null;
        }

        if (selectedBean == null || !candidates.contains(selectedBean)) {
            if (matchingBeans.size() > 1) {
                List<String> sortedNames = matchingBeans.stream()
                        .map(b -> b.getBeanClass().getName())
                        .distinct()
                        .sorted()
                        .toList();
                throw new IllegalStateException(
                        "Multiple implementations detected for SPI " + spiClass.getName() + ": " + sortedNames);
            }
            // The single matching provider is disabled or an unselected alternative in CDI
            return Optional.empty();
        }

        CreationalContext<?> ctx = beanManager.createCreationalContext(selectedBean);
        @SuppressWarnings("unchecked")
        T instance = (T) beanManager.getReference(selectedBean, spiClass, ctx);
        return Optional.ofNullable(instance);
    }
}
