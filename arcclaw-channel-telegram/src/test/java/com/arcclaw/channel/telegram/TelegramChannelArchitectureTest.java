package com.arcclaw.channel.telegram;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.arcclaw.channel.telegram")
class TelegramChannelArchitectureTest {

    @ArchTest
    static final ArchRule telegram_channel_must_not_depend_on_core_internals =
        noClasses().that().resideInAPackage("com.arcclaw.channel.telegram..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.arcclaw.core..")
            .allowEmptyShould(true)
            .because("Telegram channel must only depend on arcclaw-channel-api SPI.");
}
