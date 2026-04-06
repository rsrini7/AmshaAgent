package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;

/**
 * Enforcement test that uses reflection to ensure MdcCleaner.clearAll() 
 * covers every constant defined in MdcKeys.
 * §8.2
 */
class MdcCleanerContractTest {

    @Test
    void clearAll_mustClearEveryKeyInMdcKeys() throws Exception {
        List<String> keys = new ArrayList<>();
        
        // 1. Collect all keys from MdcKeys via reflection
        for (Field field : MdcKeys.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) 
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                keys.add((String) field.get(null));
            }
        }
        
        // 2. Put a test value for every key
        for (String key : keys) {
            MDC.put(key, "residue-from-test");
        }
        
        // 3. Execute clearAll
        MdcCleaner.clearAll();
        
        // 4. Assert every key is null
        for (String key : keys) {
            assertNull(MDC.get(key), String.format(
                "MdcCleaner.clearAll() FAILED to remove key '%s' defined in MdcKeys. " +
                "You must update MdcCleaner when adding new diagnostic keys.", key));
        }
    }
}
