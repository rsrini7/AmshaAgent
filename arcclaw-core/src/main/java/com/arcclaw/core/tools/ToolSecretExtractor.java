package com.arcclaw.core.tools;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.security.RequiresSecret;
import com.arcclaw.core.security.RequiresSecrets;
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
    public Map<String, String> extractRequiredSecrets(
            Class<? extends AgentTool> toolClass, String methodName) {
        var secrets = new LinkedHashMap<String, String>();

        // Unwrap Quarkus CDI proxies to read annotations from the true class
        Class<?> actualClass = toolClass;
        while (actualClass.getName().contains("_ClientProxy")
                || actualClass.getName().contains("_Subclass")) {
            actualClass = actualClass.getSuperclass();
        }

        try {
            Method method = actualClass.getMethod(methodName, Map.class);
            var container = method.getAnnotation(RequiresSecrets.class);
            if (container != null) {
                for (var rs : container.value()) {
                    secrets.put(rs.envVar(), rs.vaultKey());
                }
            }
            var single = method.getAnnotation(RequiresSecret.class);
            if (single != null && container == null) {
                secrets.put(single.envVar(), single.vaultKey());
            }
        } catch (NoSuchMethodException _) {
            log.warnf("Tool %s has no %s(Map) method - no secrets extracted",
                    actualClass.getName(), methodName);
        }
        return Collections.unmodifiableMap(secrets);
    }
}
