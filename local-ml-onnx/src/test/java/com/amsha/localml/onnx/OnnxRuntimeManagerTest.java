package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnnxRuntimeManagerTest {

    @Test
    void remainsUnavailableWhenFeatureIsDisabled() {
        OnnxRuntimeManager manager = new OnnxRuntimeManager();
        manager.enabled = false;

        manager.initialize();

        assertFalse(manager.isReady());
        assertEquals("disabled", manager.unavailableReason());
    }

    @Test
    void loadsRuntimeWhenFeatureIsEnabled() {
        OnnxRuntimeManager manager = new OnnxRuntimeManager();
        manager.enabled = true;

        manager.initialize();

        assertTrue(manager.isReady(), manager.unavailableReason());
        assertTrue(manager.environment().isPresent());
    }
}
