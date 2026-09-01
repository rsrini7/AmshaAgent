package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatModelListenerRuntimeControlTest {

    @Test
    @DisplayName("Initial state reflects configured mode and reports no ready implementation")
    void initialState_reflectsConfiguredMode() {
        var control = new ChatModelListenerRuntimeControl("shadow");

        assertEquals(ChatModelListenerMode.SHADOW, control.requestedMode());
        assertEquals(ChatModelListenerMode.OFF, control.effectiveMode());

        var snapshot = control.snapshot();
        assertEquals(ChatModelListenerMode.SHADOW, snapshot.requestedMode());
        assertEquals(ChatModelListenerMode.OFF, snapshot.effectiveMode());
        assertFalse(snapshot.implementationAvailable());
        assertFalse(snapshot.acceptingRequests());
        assertEquals(0, snapshot.inFlight());
    }

    @Test
    @DisplayName("Enabling SHADOW or ACTIVE without ready implementation fails closed")
    void enable_withoutReadyImplementation_failsClosed() {
        var control = new ChatModelListenerRuntimeControl("off");

        var result = control.setMode(ChatModelListenerMode.SHADOW);
        assertEquals(ChatModelListenerRuntimeControl.ToggleResult.REJECTED_MISSING_IMPLEMENTATION, result);
        assertEquals(ChatModelListenerMode.SHADOW, control.requestedMode());
        assertEquals(ChatModelListenerMode.OFF, control.effectiveMode());

        var activeResult = control.setMode(ChatModelListenerMode.ACTIVE);
        assertEquals(ChatModelListenerRuntimeControl.ToggleResult.REJECTED_MISSING_IMPLEMENTATION, activeResult);
        assertEquals(ChatModelListenerMode.ACTIVE, control.requestedMode());
        assertEquals(ChatModelListenerMode.OFF, control.effectiveMode());
    }

    @Test
    @DisplayName("Registering ready implementation activates requested mode and allows lease acquisition")
    void registerImplementation_activatesRequestedMode_andAcquiresLease() {
        var control = new ChatModelListenerRuntimeControl("shadow");

        try (var registration = control.registerReadyImplementation()) {
            assertEquals(ChatModelListenerMode.SHADOW, control.effectiveMode());
            assertTrue(control.snapshot().implementationAvailable());
            assertTrue(control.snapshot().acceptingRequests());

            Optional<ChatModelListenerRuntimeControl.Lease> leaseOpt = control.tryAcquire();
            assertTrue(leaseOpt.isPresent());
            assertEquals(ChatModelListenerMode.SHADOW, leaseOpt.get().mode());
            assertEquals(1, control.snapshot().inFlight());

            leaseOpt.get().close();
            assertEquals(0, control.snapshot().inFlight());
        }

        // After registration closes, effective mode becomes OFF
        assertEquals(ChatModelListenerMode.OFF, control.effectiveMode());
        assertFalse(control.snapshot().implementationAvailable());
        assertFalse(control.snapshot().acceptingRequests());
        assertTrue(control.tryAcquire().isEmpty());
    }

    @Test
    @DisplayName("Disabling active mode drains in-flight leases and rejects new acquisitions")
    void disable_drainsInFlightLeases() {
        var control = new ChatModelListenerRuntimeControl("active");

        try (var registration = control.registerReadyImplementation()) {
            Optional<ChatModelListenerRuntimeControl.Lease> lease1 = control.tryAcquire();
            assertTrue(lease1.isPresent());
            assertEquals(1, control.snapshot().inFlight());

            // Disable while lease is active
            var toggle = control.setMode(ChatModelListenerMode.OFF);
            assertEquals(ChatModelListenerRuntimeControl.ToggleResult.DRAINING, toggle);
            assertEquals(ChatModelListenerMode.OFF, control.effectiveMode());
            assertTrue(control.snapshot().draining());

            // New acquisitions rejected
            assertTrue(control.tryAcquire().isEmpty());

            // Closing lease completes draining
            lease1.get().close();
            assertEquals(0, control.snapshot().inFlight());
            assertFalse(control.snapshot().draining());
        }
    }
}
