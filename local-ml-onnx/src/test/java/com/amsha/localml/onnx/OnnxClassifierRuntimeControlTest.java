package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.localml.onnx.OnnxClassifierRuntimeControl.Classifier;

import org.junit.jupiter.api.Test;

class OnnxClassifierRuntimeControlTest {

    @Test
    void missingImplementationCannotBeEnabled() {
        var control = new OnnxClassifierRuntimeControl(false, false, false);

        var result = control.setEnabled(Classifier.TOOL_RISK, true);

        assertFalse(result.accepted());
        assertEquals("MISSING_READY_IMPLEMENTATION", result.reason());
        assertFalse(result.snapshot().requestedEnabled());
        assertFalse(result.snapshot().effectiveEnabled());
    }

    @Test
    void disableRejectsNewLeasesAndDrainsExistingLease() {
        var control = new OnnxClassifierRuntimeControl(false, false, false);
        try (var registration = control.registerReadyImplementation(Classifier.TOOL_RISK)) {
            assertTrue(control.setEnabled(Classifier.TOOL_RISK, true).accepted());
            var lease = control.tryAcquire(Classifier.TOOL_RISK).orElseThrow();

            var disabled = control.setEnabled(Classifier.TOOL_RISK, false);

            assertTrue(disabled.accepted());
            assertTrue(disabled.changed());
            assertFalse(disabled.snapshot().acceptingRequests());
            assertTrue(disabled.snapshot().draining());
            assertEquals(1, disabled.snapshot().inFlight());
            assertTrue(control.tryAcquire(Classifier.TOOL_RISK).isEmpty());

            lease.close();
            var drained = control.snapshot(Classifier.TOOL_RISK);
            assertFalse(drained.draining());
            assertEquals(0, drained.inFlight());
        }
    }

    @Test
    void runtimeSwitchesRemainIndependent() {
        var control = new OnnxClassifierRuntimeControl(false, false, false);
        try (var registration = control.registerReadyImplementation(Classifier.ANSWER_QUALITY)) {
            control.setEnabled(Classifier.ANSWER_QUALITY, true);

            assertTrue(control.snapshot(Classifier.ANSWER_QUALITY).effectiveEnabled());
            assertFalse(control.snapshot(Classifier.INTENT).effectiveEnabled());
            assertFalse(control.snapshot(Classifier.TOOL_RISK).effectiveEnabled());
        }
    }

    @Test
    void startupRequestRemainsIneffectiveUntilImplementationRegisters() {
        var control = new OnnxClassifierRuntimeControl(true, false, false);
        assertTrue(control.snapshot(Classifier.INTENT).requestedEnabled());
        assertFalse(control.snapshot(Classifier.INTENT).effectiveEnabled());

        try (var registration = control.registerReadyImplementation(Classifier.INTENT)) {
            assertTrue(control.snapshot(Classifier.INTENT).effectiveEnabled());
        }

        assertFalse(control.snapshot(Classifier.INTENT).effectiveEnabled());
    }

    @Test
    void registrationCloseIsIdempotent() {
        var control = new OnnxClassifierRuntimeControl(false, false, false);
        var registration = control.registerReadyImplementation(Classifier.INTENT);

        registration.close();
        registration.close();

        assertFalse(control.snapshot(Classifier.INTENT).implementationAvailable());
    }
}
