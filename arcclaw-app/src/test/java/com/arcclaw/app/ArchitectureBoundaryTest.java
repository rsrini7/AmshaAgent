package com.arcclaw.app;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.transaction.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Architectural boundary enforcement via ArchUnit.
 * Matches Tier 2-3 hardening requirements.
 */
@AnalyzeClasses(packages = "com.arcclaw", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureBoundaryTest {

    // 1. com.arcclaw.core does NOT import com.arcclaw.channel.telegram or com.arcclaw.channel.web
    @ArchTest
    static final ArchRule coreShouldNotImportChannels = noClasses()
            .that().resideInAPackage("com.arcclaw.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.arcclaw.channel.telegram..",
                    "com.arcclaw.channel.web..");

    // 2. com.arcclaw.core does NOT import com.arcclaw.persistence.sqlite (§5.14.1)
    @ArchTest
    static final ArchRule coreShouldNotImportSqlite = noClasses()
            .that().resideInAPackage("com.arcclaw.core..")
            .should().dependOnClassesThat().resideInAPackage("com.arcclaw.persistence.sqlite..");

    // 3. Channel modules do NOT import com.arcclaw.core.* directly (§1.4 SPI)
    @ArchTest
    static final ArchRule channelsShouldNotImportCoreDirectly = noClasses()
            .that().resideInAPackage("com.arcclaw.channel..")
            .and().resideOutsideOfPackage("com.arcclaw.channel.api..")
            .should().dependOnClassesThat().resideInAPackage("com.arcclaw.core..");

    // 4. com.arcclaw.core does NOT import com.arcclaw.app (§16.1)
    @ArchTest
    static final ArchRule coreShouldNotImportApp = noClasses()
            .that().resideInAPackage("com.arcclaw.core..")
            .should().dependOnClassesThat().resideInAPackage("com.arcclaw.app..");

    // 5. IntentClassifier implementations reside in com.arcclaw.core.agent..* NOT in com.arcclaw.app..* (§20.1)
    @ArchTest
    static final ArchRule intentClassifiersShouldResideInCore = classes()
            .that().haveSimpleNameEndingWith("IntentClassifier")
            .should().resideInAPackage("com.arcclaw.core.agent..");

    // 6. No class in com.arcclaw.core.agent.loop..* is annotated with @Transactional
    @ArchTest
    static final ArchRule loopShouldBeTransactionFree = noClasses()
            .that().resideInAPackage("com.arcclaw.core.agent.loop..")
            .should().beAnnotatedWith(Transactional.class);
    
    // 7. No method in com.arcclaw.core.agent.loop..* is annotated with @Transactional
    @ArchTest
    static final ArchRule loopMethodsShouldBeTransactionFree =
        noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("com.arcclaw.core.agent.loop..")
                .should().beAnnotatedWith(Transactional.class);            

}
