package com.amsha.security.api;

import java.lang.annotation.*;

/**
 * Marks a tool method as requiring a specific secret from the vault.
 * <p>
 * The secret identified by {@link #vaultKey()} will be resolved from
 * {@link SecretVault} and injected into the container environment
 * as the variable named {@link #envVar()}.
 * <p>
 * This annotation is {@link Repeatable} - a method can declare
 * multiple required secrets.
 *
 * <pre>
 * {@literal @}RequiresSecret(envVar = "GITHUB_TOKEN", vaultKey = "GITHUB_TOKEN")
 * {@literal @}RequiresSecret(envVar = "NPM_TOKEN",    vaultKey = "NPM_REGISTRY_TOKEN")
 * public String runBuild(Path workspace) { ... }
 * </pre>
 */
@Repeatable(RequiresSecrets.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresSecret {

    /**
     * The environment variable name exposed inside the container.
     */
    String envVar();

    /**
     * The vault key name used to look up the secret via
     * {@link SecretVault#get(String)}.
     */
    String vaultKey();
}
