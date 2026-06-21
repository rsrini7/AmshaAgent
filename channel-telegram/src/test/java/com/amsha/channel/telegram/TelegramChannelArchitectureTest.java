package com.amsha.channel.telegram;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.amsha.channel.telegram")
class TelegramChannelArchitectureTest {

    @ArchTest
    static final ArchRule telegram_channel_must_not_depend_on_core_internals = noClasses()
            .that()
            .resideInAPackage("com.amsha.channel.telegram..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core..")
            .allowEmptyShould(true)
            .because("Telegram channel must only depend on amsha-channel-api SPI.");
}
