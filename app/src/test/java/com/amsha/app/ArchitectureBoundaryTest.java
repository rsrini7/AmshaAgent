package com.amsha.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import jakarta.transaction.Transactional;

import com.amsha.channel.api.AbstractInboundMessagePipeline;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architectural boundary enforcement via ArchUnit.
 * Matches Tier 2-3 hardening requirements.
 */
@AnalyzeClasses(packages = "com.amsha", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureBoundaryTest {

    // 1. com.amsha.core does NOT import com.amsha.channel.telegram or com.amsha.channel.web
    @ArchTest
    static final ArchRule coreShouldNotImportChannels = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.amsha.channel.telegram..", "com.amsha.channel.web..")
            .allowEmptyShould(true);

    // 2. com.amsha.core does NOT import com.amsha.persistence.sqlite (§5.14.1)
    @ArchTest
    static final ArchRule coreShouldNotImportSqlite = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.persistence.sqlite..")
            .allowEmptyShould(true);

    // 3. Channel modules do NOT import com.amsha.core.* directly (§1.4 SPI)
    @ArchTest
    static final ArchRule channelsShouldNotImportCoreDirectly = noClasses()
            .that()
            .resideInAPackage("com.amsha.channel..")
            .and()
            .resideOutsideOfPackage("com.amsha.channel.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core..")
            .allowEmptyShould(true);

    // 4. com.amsha.core does NOT import com.amsha.app (§16.1)
    @ArchTest
    static final ArchRule coreShouldNotImportApp = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.app..")
            .allowEmptyShould(true);

    // 5. IntentDetector implementations reside in com.amsha.core.routing..* (§20.1)
    @ArchTest
    static final ArchRule intentDetectorsShouldResideInCore = classes()
            .that()
            .haveSimpleNameEndingWith("IntentDetector")
            .and()
            .resideOutsideOfPackage("com.amsha.channel.api..")
            .should()
            .resideInAPackage("com.amsha.core.routing..")
            .allowEmptyShould(true);

    // 6. No class in com.amsha.core.agent.loop..* is annotated with @Transactional
    @ArchTest
    static final ArchRule loopShouldBeTransactionFree = noClasses()
            .that()
            .resideInAPackage("com.amsha.core.agent.loop..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    // 7. No method in com.amsha.core.agent.loop..* is annotated with @Transactional
    @ArchTest
    static final ArchRule loopMethodsShouldBeTransactionFree = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("com.amsha.core.agent.loop..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    // 8. com.amsha.core does NOT depend on jakarta.persistence.EntityManager (§5.14.1)
    @ArchTest
    static final ArchRule anyPackageShouldNotUseEntityManager = noClasses()
            .that()
            .resideInAPackage("com.amsha..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("jakarta.persistence.EntityManager")
            .allowEmptyShould(true);

    // 9. isProcessing() is for test verification only — ban calls from production code
    @ArchTest
    static final ArchRule isProcessingTestSeam = noClasses()
            .that()
            .resideOutsideOfPackage("..test..")
            .should()
            .callMethod(AbstractInboundMessagePipeline.class, "isProcessing", String.class)
            .allowEmptyShould(true);
}
