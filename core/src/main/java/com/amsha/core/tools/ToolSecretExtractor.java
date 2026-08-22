package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.security.api.RequiresSecret;
import com.amsha.security.api.RequiresSecrets;
import com.amsha.tool.api.AgentTool;

import org.jboss.logging.Logger;

/**
 * Reflectively extracts required secrets from tool classes based on annotations.
 */
@ApplicationScoped
public class ToolSecretExtractor {

    private static final Logger log = Logger.getLogger(ToolSecretExtractor.class);

    /**
     * Reflectively reads {@link RequiresSecret} annotations from the named method
     * of a tool class to build the env-var → vault-key mapping.
     */
    public Map<String, String> extractRequiredSecrets(Class<? extends AgentTool> toolClass, String methodName) {
        var secrets = new LinkedHashMap<String, String>();

        // Unwrap Quarkus CDI proxies to read annotations from the true class
        Class<?> actualClass = toolClass;
        while (actualClass.getName().contains("_ClientProxy")
                || actualClass.getName().contains("_Subclass")) {
            actualClass = actualClass.getSuperclass();
        }
        if (actualClass != toolClass) {
            log.debugf("Unwrapped proxy %s to actual class %s", toolClass.getSimpleName(), actualClass.getSimpleName());
        }

        for (Method m : actualClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                var container = m.getAnnotation(RequiresSecrets.class);
                if (container != null) {
                    for (var rs : container.value()) {
                        secrets.put(rs.envVar(), rs.vaultKey());
                    }
                }
                var single = m.getAnnotation(RequiresSecret.class);
                if (single != null) {
                    secrets.put(single.envVar(), single.vaultKey());
                }
            }
        }
        if (secrets.isEmpty()) {
            for (Method m : actualClass.getMethods()) {
                if (m.getName().equals(methodName)) {
                    var container = m.getAnnotation(RequiresSecrets.class);
                    if (container != null) {
                        for (var rs : container.value()) {
                            secrets.put(rs.envVar(), rs.vaultKey());
                        }
                    }
                    var single = m.getAnnotation(RequiresSecret.class);
                    if (single != null) {
                        secrets.put(single.envVar(), single.vaultKey());
                    }
                }
            }
        }
        if (!secrets.isEmpty()) {
            log.debugf(
                    "Extracted %d required secrets from %s.%s",
                    secrets.size(), actualClass.getSimpleName(), methodName);
        }
        return Collections.unmodifiableMap(secrets);
    }
}
