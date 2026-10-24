package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.channel.telegram.TelegramAllowlistGuard;
import com.amsha.common.config.ConfigKeys;
import com.amsha.security.api.SecretVault;

import org.junit.jupiter.api.Test;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import io.quarkus.test.component.TestConfigProperty;

@QuarkusComponentTest({StartupSecurityValidator.class, TelegramAllowlistGuard.class})
@TestConfigProperty(key = "quarkus.profile", value = "test")
@TestConfigProperty(key = "amsha.vault.pepper", value = "safe-pepper-for-boot-000000000000")
@TestConfigProperty(key = "amsha.channel.telegram.enabled", value = "false")
@TestConfigProperty(key = ConfigKeys.TELEGRAM_ALLOWED_USER_IDS_PROPERTY, value = "<telegram-user-ids-comma-seperated>")
class StartupSecurityValidatorTelegramPlaceholderConfigTest {

    private static final String TELEGRAM_USER_IDS_PLACEHOLDER = "<telegram-user-ids-comma-seperated>";

    @Inject
    StartupSecurityValidator validator;

    @Inject
    TelegramAllowlistGuard allowlistGuard;

    @InjectMock
    SecretVault vault;

    @Test
    void placeholderAllowlistDoesNotBreakStartupWhenTelegramIsDisabled() {
        StartupSecurityValidator realValidator = ClientProxy.unwrap(validator);

        assertFalse(realValidator.telegramEnabled);
        assertEquals(Optional.of(List.of(TELEGRAM_USER_IDS_PLACEHOLDER)), realValidator.allowedUserIds);
        assertFalse(allowlistGuard.isAllowed("123456", "nobody"));
    }
}
