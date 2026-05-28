package com.arcclaw.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

/**
 * ArchUnit enforcement for the Heterogeneous Nullability rule on
 * {@link ReasoningStep#success()}.
 *
 * <p>{@code ReasoningStep.success()} returns a nullable {@link Boolean}.
 * All consumers MUST use one of these null-safe patterns:
 * <ul>
 *   <li>{@code Boolean.TRUE.equals(step.success())}</li>
 *   <li>{@code Boolean.FALSE.equals(step.success())}</li>
 *   <li>{@code if (step.success() != null) ...}</li>
 * </ul>
 *
 * <p>Raw auto-unboxing ({@code if (step.success())}) is forbidden because it causes NPE
 * when the step is a {@link ReasoningStep.ParallelStep} or a fast-path/pending step
 * with unset success.
 *
 * <p>This test enumerates all classes that call {@code ReasoningStep.success()} and
 * reports them for manual review. Verified safe classes are listed in
 * {@link #VERIFIED_SAFE_CLASSES}. Any new class not in this list will fail the test,
 * forcing the developer to verify null-safety and add it to the allowlist.
 *
 * @see ReasoningStep#success()
 */
class ReasoningStepSuccessNullSafetyTest {

    /**
     * Classes that call {@code ReasoningStep.success()} and have been verified null-safe.
     * Each entry must include a comment explaining the guard pattern used.
     */
    private static final Set<String> VERIFIED_SAFE_CLASSES = Set.of(
            // Uses Boolean.TRUE.equals() — null-safe
            "com.arcclaw.core.agent.loop.FinishActionHandler",
            // Uses Boolean.TRUE.equals() — null-safe
            "com.arcclaw.core.agent.loop.FinishOutcomeProcessor",
            // Uses Boolean.TRUE.equals() — null-safe
            "com.arcclaw.core.agent.loop.EngineStepHandler",
            // Uses Boolean.TRUE.equals() / Boolean.FALSE.equals() — null-safe
            "com.arcclaw.core.agent.reasoning.LangChainReasoningEngine",
            // Uses if (value.success() != null) — null-safe
            "com.arcclaw.core.agent.reasoning.ReasoningStepSerializer",
            // Uses if (last.success() != null) — null-safe
            "com.arcclaw.core.agent.loop.LoopResultHandler",
            // Passes step.success() to constructor (null-safe — DTO accepts null Boolean)
            "com.arcclaw.core.state.CheckpointSerializer",
            // Uses Boolean.TRUE.equals() — null-safe (fixed 2026-05-28)
            "com.arcclaw.core.observability.AgentTraceEnricher");

    /**
     * Enumerates all classes that call {@code ReasoningStep.success()} and verifies
     * each is in the verified-safe allowlist. New callers must be reviewed for
     * null-safety and added to {@link #VERIFIED_SAFE_CLASSES}.
     */
    /**
     * Verifies that {@link ReasoningStep#success()} is annotated {@code @Nullable}.
     * This is the compile-time contract that all consumers must honor.
     */
    @Test
    void success_methodMustBeAnnotatedNullable() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.arcclaw.core");

        var step = classes.get(ReasoningStep.class.getName());
        var successMethod = step.getMethods().stream()
                .filter(m -> m.getName().equals("success"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ReasoningStep.success() method not found"));

        boolean hasNullable = successMethod.getAnnotations().stream()
                .anyMatch(a -> a.getType().getName().contains("Nullable"));

        assertTrue(
                hasNullable,
                "ReasoningStep.success() must be annotated @Nullable. "
                        + "See AGENTS.md §1.5 — Heterogeneous Nullability in Sealed Interfaces.");
    }

    @Test
    void allReasoningStepSuccessCallers_areVerifiedNullSafe() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.arcclaw.core");

        var callers = classes.stream()
                .filter(javaClass -> javaClass.getMethods().stream()
                        .flatMap(m -> m.getCallsFromSelf().stream())
                        .anyMatch(call -> "success".equals(call.getName())
                                && call.getTargetOwner().isAssignableTo(ReasoningStep.class)))
                .map(JavaClass::getName)
                .filter(name -> !name.equals(ReasoningStep.class.getName())) // exclude the interface itself
                .toList();

        var unverified = callers.stream()
                .filter(name -> !VERIFIED_SAFE_CLASSES.contains(name))
                .toList();

        assertTrue(
                unverified.isEmpty(),
                "New classes calling ReasoningStep.success() must be verified null-safe "
                        + "and added to VERIFIED_SAFE_CLASSES in this test. "
                        + "See AGENTS.md §1.5 for the rule. Unverified: " + unverified);
    }
}
