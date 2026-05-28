package com.arcclaw.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import jakarta.transaction.Transactional;

import com.arcclaw.channel.api.AbstractInboundMessagePipeline;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architectural boundary enforcement via ArchUnit.
 * Matches Tier 2-3 hardening requirements.
 */
@AnalyzeClasses(packages = "com.arcclaw", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureBoundaryTest {

    // 1. com.arcclaw.core does NOT import com.arcclaw.channel.telegram or com.arcclaw.channel.web
    @ArchTest
    static final ArchRule coreShouldNotImportChannels = noClasses()
            .that()
            .resideInAPackage("com.arcclaw.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.arcclaw.channel.telegram..", "com.arcclaw.channel.web..")
            .allowEmptyShould(true);

    // 2. com.arcclaw.core does NOT import com.arcclaw.persistence.sqlite (§5.14.1)
    @ArchTest
    static final ArchRule coreShouldNotImportSqlite = noClasses()
            .that()
            .resideInAPackage("com.arcclaw.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.arcclaw.persistence.sqlite..")
            .allowEmptyShould(true);

    // 3. Channel modules do NOT import com.arcclaw.core.* directly (§1.4 SPI)
    @ArchTest
    static final ArchRule channelsShouldNotImportCoreDirectly = noClasses()
            .that()
            .resideInAPackage("com.arcclaw.channel..")
            .and()
            .resideOutsideOfPackage("com.arcclaw.channel.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.arcclaw.core..")
            .allowEmptyShould(true);

    // 4. com.arcclaw.core does NOT import com.arcclaw.app (§16.1)
    @ArchTest
    static final ArchRule coreShouldNotImportApp = noClasses()
            .that()
            .resideInAPackage("com.arcclaw.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.arcclaw.app..")
            .allowEmptyShould(true);

    // 5. IntentDetector implementations reside in com.arcclaw.core.routing..* (§20.1)
    @ArchTest
    static final ArchRule intentDetectorsShouldResideInCore = classes()
            .that()
            .haveSimpleNameEndingWith("IntentDetector")
            .and()
            .resideOutsideOfPackage("com.arcclaw.channel.api..")
            .should()
            .resideInAPackage("com.arcclaw.core.routing..")
            .allowEmptyShould(true);

    // 6. No class in com.arcclaw.core.agent.loop..* is annotated with @Transactional
    @ArchTest
    static final ArchRule loopShouldBeTransactionFree = noClasses()
            .that()
            .resideInAPackage("com.arcclaw.core.agent.loop..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    // 7. No method in com.arcclaw.core.agent.loop..* is annotated with @Transactional
    @ArchTest
    static final ArchRule loopMethodsShouldBeTransactionFree = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("com.arcclaw.core.agent.loop..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    // 8. com.arcclaw.core does NOT depend on jakarta.persistence.EntityManager (§5.14.1)
    @ArchTest
    static final ArchRule coreShouldNotUseEntityManager = noClasses()
            .that()
            .resideInAPackage("com.arcclaw.core..")
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
