package com.arcclaw.channel.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.arcclaw.channel.web")
class WebChannelArchitectureTest {

    @ArchTest
    static final ArchRule web_channel_must_not_depend_on_core_internals =
        noClasses().that().resideInAPackage("com.arcclaw.channel.web..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.arcclaw.core..");
}
