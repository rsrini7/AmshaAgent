package com.amsha.security.api;

import java.lang.annotation.*;

/**
 * Container annotation for {@link RequiresSecret}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresSecrets {
    RequiresSecret[] value();
}
