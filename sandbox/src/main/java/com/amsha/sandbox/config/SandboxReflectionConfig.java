package com.amsha.sandbox.config;

import com.amsha.sandbox.PodmanUnavailableException;
import com.amsha.sandbox.SandboxImageMissingException;
import com.amsha.sandbox.SandboxImageResolver;
import com.amsha.sandbox.SandboxStrategy;
import com.amsha.sandbox.SecretTransport;
import com.amsha.sandbox.TmpfsSecretTransport;
import com.amsha.sandbox.ToolExecutionException;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            PodmanUnavailableException.class,
            SandboxImageMissingException.class,
            SandboxImageResolver.class,
            SandboxStrategy.class,
            SecretTransport.class,
            TmpfsSecretTransport.class,
            ToolExecutionException.class,
        },
        classNames = {"com.amsha.sandbox.SandboxCircuitBreaker$CircuitBreakerState"})
public class SandboxReflectionConfig {}
