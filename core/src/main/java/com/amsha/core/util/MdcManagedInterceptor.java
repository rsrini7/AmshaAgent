package com.amsha.core.util;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

@MdcManaged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class MdcManagedInterceptor {

    @AroundInvoke
    public Object manageMdc(InvocationContext context) throws Exception {
        try {
            return context.proceed();
        } finally {
            MdcCleaner.clearAll();
        }
    }
}
