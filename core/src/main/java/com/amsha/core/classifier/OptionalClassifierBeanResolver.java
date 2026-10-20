package com.amsha.core.classifier;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

import com.amsha.classifier.api.ProviderPlacementValidator;

/** Resolves optional classifier SPIs without requiring their implementation modules. */
public final class OptionalClassifierBeanResolver {

    private OptionalClassifierBeanResolver() {}

    public static <T> List<T> resolveOrdered(
            BeanManager beanManager, Class<T> spiType, ToIntFunction<T> priorityResolver) {
        Objects.requireNonNull(beanManager, "beanManager");
        Objects.requireNonNull(spiType, "spiType");
        Set<Bean<?>> beans = beanManager.getBeans(Object.class);
        List<T> resolved = beans.stream()
                .filter(bean -> implementsTypeName(bean.getBeanClass(), spiType.getName()))
                .map(bean -> resolve(beanManager, bean, spiType))
                .filter(Objects::nonNull)
                .toList();
        return order(resolved, priorityResolver);
    }

    public static <T> List<T> order(List<T> implementations, ToIntFunction<T> priorityResolver) {
        Objects.requireNonNull(implementations, "implementations");
        Objects.requireNonNull(priorityResolver, "priorityResolver");
        return implementations.stream()
                .filter(Objects::nonNull)
                .sorted((left, right) -> {
                    int priorityOrder = Integer.compare(
                            safePriority(left, priorityResolver), safePriority(right, priorityResolver));
                    return priorityOrder != 0
                            ? priorityOrder
                            : binaryClassName(left).compareTo(binaryClassName(right));
                })
                .toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> T resolve(BeanManager beanManager, Bean<?> bean, Class<T> spiType) {
        try {
            Bean typed = bean;
            Object reference = beanManager.getReference(typed, spiType, beanManager.createCreationalContext(typed));
            if (!spiType.isInstance(reference)) {
                return null;
            }
            T instance = spiType.cast(reference);
            ProviderPlacementValidator.validateClassifierSpiPlacement(instance);
            return instance;
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean implementsTypeName(Class<?> candidate, String expectedName) {
        if (candidate == null || candidate == Object.class) return false;
        for (Class<?> implemented : candidate.getInterfaces()) {
            if (implemented.getName().equals(expectedName) || implementsTypeName(implemented, expectedName)) {
                return true;
            }
        }
        return implementsTypeName(candidate.getSuperclass(), expectedName);
    }

    private static <T> int safePriority(T implementation, ToIntFunction<T> priorityResolver) {
        try {
            return priorityResolver.applyAsInt(implementation);
        } catch (RuntimeException | LinkageError ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String binaryClassName(Object implementation) {
        Class<?> type = implementation.getClass();
        while ((type.getName().contains("_ClientProxy") || type.getName().contains("_Subclass"))
                && type.getSuperclass() != null
                && type.getSuperclass() != Object.class) {
            type = type.getSuperclass();
        }
        return type.getName();
    }
}
