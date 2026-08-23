package com.amsha.skills;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-skills.
 *
 * <p>Nested types populated by reflective YAML parsing are registered under {@code classNames} with
 * binary names per the native reflection rule (nested types never go in {@code targets}).</p>
 *
 * <p>Contract records ({@code CompiledSkillEntry}, {@code SkillDefinition}, {@code SkillFrontmatter},
 * {@code SkillPromptParams}) remain registered in core's {@code CoreReflectionConfig} — they are consumed
 * by prompt assembly inside core.</p>
 */
@RegisterForReflection(
        classNames = {
            "com.amsha.skills.SkillLoader$QuarantinedSkill",
            "com.amsha.skills.SkillAdmissionEvaluator$AdmissionDecision"
        })
public class SkillsReflectionConfig {}
