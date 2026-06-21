package com.amsha.channel.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces architectural boundaries for the web channel.
 * §4.5 §1.1
 */
@AnalyzeClasses(packages = "com.amsha.channel.web")
public class WebChannelArchitectureTest {

    @ArchTest
    static final ArchRule web_channel_must_not_depend_on_core_internals = noClasses()
            .that()
            .resideInAPackage("com.amsha.channel.web..")
            // Ignore test-only mock producers that bridge core/channel for integration testing
            .and()
            .haveSimpleNameNotEndingWith("MockChatModelProducer")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core..")
            .allowEmptyShould(true);
}
