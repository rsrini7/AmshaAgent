package com.amsha.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import jakarta.transaction.Transactional;

import com.amsha.channel.api.AbstractInboundMessagePipeline;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architectural boundary enforcement via ArchUnit.
 * Matches Tier 2-3 hardening requirements.
 *
 * <p>Two-tier boundary policy (extract-core-subsystems, ADR §2.1/§4.1):
 * <ul>
 *   <li><b>API tier</b> ({@code llm-provider-api}, {@code persistence-api}, {@code channel-api},
 *       {@code tool-api}, {@code classifier-api}, {@code security-api}): zero {@code com.amsha.core} imports.</li>
 *   <li><b>Impl tier</b> ({@code sandbox}, {@code tools-builtin}, {@code amsha-security},
 *       {@code amsha-skills}): may depend on core when consuming core services; core must never import an
 *       impl module. {@code com.amsha.memory} is the exempted zero-core impl consumed by core
 *       (legal core → memory edge).</li>
 * </ul>
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

    // 10. com.amsha.core does NOT import com.amsha.llm.providers..
    @ArchTest
    static final ArchRule coreShouldNotImportLlmProviders = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.llm.providers..")
            .allowEmptyShould(true);

    // 11. com.amsha.llm.api does NOT import com.amsha.core.. or com.amsha.llm.providers..
    @ArchTest
    static final ArchRule llmProviderApiShouldNotImportCoreOrProviders = noClasses()
            .that()
            .resideInAPackage("com.amsha.llm.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.amsha.core..", "com.amsha.llm.providers..")
            .allowEmptyShould(true);

    // 12. com.amsha.llm.providers does NOT import com.amsha.app..
    @ArchTest
    static final ArchRule llmProvidersShouldNotImportApp = noClasses()
            .that()
            .resideInAPackage("com.amsha.llm.providers..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.app..")
            .allowEmptyShould(true);

    // 13. com.amsha.onboarding does NOT import com.amsha.llm.providers..
    @ArchTest
    static final ArchRule onboardingShouldNotImportLlmProviders = noClasses()
            .that()
            .resideInAPackage("com.amsha.onboarding..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.llm.providers..")
            .allowEmptyShould(true);

    // 14. com.amsha.core does NOT import com.amsha.sandbox..
    @ArchTest
    static final ArchRule coreShouldNotImportSandbox = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.sandbox..")
            .allowEmptyShould(true);

    // 15. com.amsha.core does NOT import com.amsha.tools.builtin..
    @ArchTest
    static final ArchRule coreShouldNotImportToolsBuiltin = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.tools.builtin..")
            .allowEmptyShould(true);

    // 16. API tier: api modules must NOT depend on com.amsha.core..
    //     (llm-api is fenced by rule 11; persistence-api/channel-api added after the S9 deep-dive
    //     review closed the fence-coverage asymmetry; tool-api Issue 05, classifier-api Issue 06,
    //     security-api vocabulary Issue 04)
    @ArchTest
    static final ArchRule apiModulesShouldNotImportCore = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.amsha.tool.api..",
                    "com.amsha.classifier.api..",
                    "com.amsha.security.api..",
                    "com.amsha.persistence.api..",
                    "com.amsha.channel.api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core..")
            .allowEmptyShould(true);

    // 17. Impl tier: com.amsha.core does NOT import the amsha-security impl (module created Issue 07)
    //     — but depending on the security-api vocabulary (com.amsha.security.api..)
    //     is the entire point of S4, so it is explicitly excluded from the target side.
    @ArchTest
    static final ArchRule coreShouldNotImportSecurityImpl = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat(new DescribedPredicate<JavaClass>("reside in security impl packages") {
                @Override
                public boolean test(JavaClass clazz) {
                    String pkg = clazz.getPackageName();
                    return pkg.startsWith("com.amsha.security") && !pkg.startsWith("com.amsha.security.api");
                }
            })
            .allowEmptyShould(true);

    // 18. Impl tier: com.amsha.core does NOT import the amsha-skills machinery
    //     (module created Issue 09; contracts stay in core.skill)
    @ArchTest
    static final ArchRule coreShouldNotImportSkillsImpl = noClasses()
            .that()
            .resideInAPackage("com.amsha.core..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.skills..")
            .allowEmptyShould(true);

    // 19. Impl tier: persistence store implementations must NOT depend on core
    //     (§5 — persistence knows only persistence-api; activated with Issue 02)
    @ArchTest
    static final ArchRule persistenceImplsShouldNotImportCore = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.amsha.persistence.sqlite..", "com.amsha.persistence.fs..", "com.amsha.persistence.postgres..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core..")
            .allowEmptyShould(true);

    // 20. Impl tier: amsha-memory is the one strict zero-core impl module (ADR §4.1 — the legal
    //     direction is core -> memory, never memory -> core; activated with Issue 08)
    @ArchTest
    static final ArchRule memoryMustNotImportCore = noClasses()
            .that()
            .resideInAPackage("com.amsha.memory..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core..")
            .allowEmptyShould(true);

    // 21. Impl tier: amsha-skills machinery must NOT depend on core reflexes (reflexes are core command
    //     dispatch; skills consume core contracts via core.skill — activated with Issue 09)
    @ArchTest
    static final ArchRule skillsMustNotImportCoreReflex = noClasses()
            .that()
            .resideInAPackage("com.amsha.skills..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.amsha.core.reflex..")
            .allowEmptyShould(true);
}
