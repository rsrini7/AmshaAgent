package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.interceptor.InvocationContext;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MdcManagedInterceptorTest {

    private final MdcManagedInterceptor interceptor = new MdcManagedInterceptor();

    @AfterEach
    void cleanup() {
        MdcCleaner.clearAll();
    }

    @Test
    void testMdcIsClearedAfterNormalExecution() throws Exception {
        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.proceed()).thenAnswer(invocation -> {
            MDC.put(MdcKeys.SESSION_ID, "test-session");
            MDC.put(MdcKeys.USER_ID, "test-user");
            MDC.put(MdcKeys.ROLE, "test-role");
            return "SUCCESS";
        });

        Object result = interceptor.manageMdc(ctx);

        assertEquals("SUCCESS", result);
        assertNull(MDC.get(MdcKeys.SESSION_ID), "SESSION_ID must be cleared");
        assertNull(MDC.get(MdcKeys.USER_ID), "USER_ID must be cleared");
        assertNull(MDC.get(MdcKeys.ROLE), "ROLE must be cleared");
    }

    @Test
    void testMdcIsClearedAfterException() throws Exception {
        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.proceed()).thenAnswer(invocation -> {
            MDC.put(MdcKeys.SESSION_ID, "fail-session");
            throw new RuntimeException("Test Failure");
        });

        assertThrows(RuntimeException.class, () -> interceptor.manageMdc(ctx));
        
        assertNull(MDC.get(MdcKeys.SESSION_ID), "SESSION_ID must be cleared even after exception");
    }
}
