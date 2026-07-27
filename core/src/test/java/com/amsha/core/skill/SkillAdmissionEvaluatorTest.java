package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillAdmissionEvaluatorTest {

    private final SkillAdmissionEvaluator evaluator = new SkillAdmissionEvaluator();

    @TempDir
    Path tempDir;

    @Test
    void rejectsArtifactWorkflowThatDependsOnUnsupportedSequentialTools() {
        SkillDefinition definition = definition(
                "algorithmic-art",
                "Use the Read tool to load templates/viewer.html, then create a single HTML artifact.",
                tempDir);

        SkillAdmissionEvaluator.AdmissionDecision decision = evaluator.evaluate(definition, "skillsjars");

        assertFalse(decision.accepted());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("Read/Write")));
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.contains("one-shot")));
    }

    @Test
    void acceptsArtifactWorkflowWithOneShotScript() throws Exception {
        Path scripts = Files.createDirectory(tempDir.resolve("scripts"));
        Files.writeString(scripts.resolve("generate.sh"), "#!/bin/sh\n");
        SkillDefinition definition = definition(
                "one-shot-art", "Generate an HTML artifact by invoking the packaged one-shot script.", tempDir);

        SkillAdmissionEvaluator.AdmissionDecision decision = evaluator.evaluate(definition, "skillsjars");

        assertTrue(decision.accepted(), decision.reasons().toString());
    }

    @Test
    void doesNotApplyThirdPartyAdmissionRulesToDatabaseSkills() {
        SkillDefinition definition = definition("local", "Use the Read tool.", null);

        assertTrue(evaluator.evaluate(definition, "database").accepted());
    }

    private static SkillDefinition definition(String name, String prompt, Path home) {
        return new SkillDefinition(
                null, name, "description", List.of(), List.of(), 5, prompt, home == null ? null : home.toString());
    }
}
