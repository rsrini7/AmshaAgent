package com.arcclaw.core.reflex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines the execution priority for a reflex.
 * Lower numbers execute first in both Pass 1 (Matching) and Pass 2 (Sanitization).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ReflexPriority {
    int value();
}
