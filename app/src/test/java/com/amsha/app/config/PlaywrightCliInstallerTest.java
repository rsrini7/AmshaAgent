package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlaywrightCliInstallerTest {

    @Test
    void testEnsureCliAndBrowsersInstalledDoesNotThrow() {
        assertDoesNotThrow(() -> PlaywrightCliInstaller.ensureCliInstalled());
        assertDoesNotThrow(() -> PlaywrightCliInstaller.ensureBrowsersInstalled());

        System.setProperty("AMSHA_TOOLS_PLAYWRIGHT_SKIP_INSTALL", "true");
        try {
            assertDoesNotThrow(() -> PlaywrightCliInstaller.ensureBrowsersInstalled());
        } finally {
            System.clearProperty("AMSHA_TOOLS_PLAYWRIGHT_SKIP_INSTALL");
        }
    }

    @Test
    void testPrivateUtilityMethodsViaReflection() throws Exception {
        Method isWindows = PlaywrightCliInstaller.class.getDeclaredMethod("isWindows");
        isWindows.setAccessible(true);
        assertNotNull(isWindows.invoke(null));

        Method platformCommand = PlaywrightCliInstaller.class.getDeclaredMethod("platformCommand", List.class);
        platformCommand.setAccessible(true);
        List<?> cmd = (List<?>) platformCommand.invoke(null, List.of("echo", "test"));
        assertNotNull(cmd);
        assertFalse(cmd.isEmpty());

        Method isNodeAvailable = PlaywrightCliInstaller.class.getDeclaredMethod("isNodeAvailable");
        isNodeAvailable.setAccessible(true);
        assertDoesNotThrow(() -> isNodeAvailable.invoke(null));

        Method isPlaywrightCliAvailable = PlaywrightCliInstaller.class.getDeclaredMethod("isPlaywrightCliAvailable");
        isPlaywrightCliAvailable.setAccessible(true);
        assertDoesNotThrow(() -> isPlaywrightCliAvailable.invoke(null));

        Method isChromiumInstalled = PlaywrightCliInstaller.class.getDeclaredMethod("isChromiumInstalled");
        isChromiumInstalled.setAccessible(true);
        assertDoesNotThrow(() -> isChromiumInstalled.invoke(null));
    }
}
