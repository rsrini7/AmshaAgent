package com.arcclaw.channel.api.structured;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Marks a Single Abstract Method (SAM) interface as a BAML-style agent function.
 * The build-time processor will generate a JSON schema from the return type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentFunction {
    /**
     * Optional description of the function's purpose for the prompt.
     */
    String value() default "";
}
