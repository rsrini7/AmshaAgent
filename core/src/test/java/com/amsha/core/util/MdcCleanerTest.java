package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;

class MdcCleanerTest {

    @Test
    void clear_removesOnlySpecifiedKeys_leavesOthersIntact() {
        // Setup
        MDC.put(MdcKeys.USER_ID, "should-survive");
        MDC.put(MdcKeys.PARALLEL_BATCH_SIZE, "should-be-removed");

        // Execute
        MdcCleaner.clear(MdcKeys.PARALLEL_BATCH_SIZE);

        // Verify
        assertNull(MDC.get(MdcKeys.PARALLEL_BATCH_SIZE));
        assertEquals("should-survive", MDC.get(MdcKeys.USER_ID));

        // Cleanup
        MDC.remove(MdcKeys.USER_ID);
    }

    @Test
    void clearAll_removesAllKeys() {
        for (String key : new String[] {MdcKeys.USER_ID, MdcKeys.SESSION_ID, MdcKeys.CHAT_ID}) {
            MDC.put(key, "test");
        }

        MdcCleaner.clearAll();

        assertNull(MDC.get(MdcKeys.USER_ID));
        assertNull(MDC.get(MdcKeys.SESSION_ID));
        assertNull(MDC.get(MdcKeys.CHAT_ID));
    }
}
