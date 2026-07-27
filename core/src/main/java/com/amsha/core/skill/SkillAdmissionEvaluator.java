package com.amsha.core.skill;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Evaluates third-party skills for compatibility with Amsha's single-tool execution model. */
@ApplicationScoped
public class SkillAdmissionEvaluator {

    private static final Pattern UNSUPPORTED_AGENT_TOOL =
            Pattern.compile("(?i)(?:using|use|with)\\s+(?:the\\s+)?(?:`)?(?:read|write|edit|glob|grep)(?:`)?\\s+tool|"
                    + "\\b(?:read|write|edit|glob|grep)\\s*\\([^)]*\\)");
    private static final Pattern ARTIFACT_OUTPUT =
            Pattern.compile("(?is)\\b(?:create|generate|write|save|output|produce)\\b.{0,80}"
                    + "\\b(?:file|html|javascript|artifact|image|pdf|document|viewer)\\b");
    private static final Pattern TEMPLATE_READ =
            Pattern.compile("(?is)\\b(?:read|load|copy)\\b.{0,80}\\btemplate(?:s)?\\b");

    public AdmissionDecision evaluate(SkillDefinition definition, String source) {
        if (!"skillsjars".equals(source)) {
            return AdmissionDecision.allow();
        }

        List<String> reasons = new ArrayList<>();
        String prompt = definition.systemPrompt();
        Path skillHome = resolveSkillHome(definition, reasons);
        List<Path> scripts = findScripts(skillHome);

        if (UNSUPPORTED_AGENT_TOOL.matcher(prompt).find()) {
            reasons.add("instructions require agent tools (Read/Write/Edit/Glob/Grep) that Amsha does not expose");
        }

        boolean producesArtifacts =
                definition.browserPreview() || ARTIFACT_OUTPUT.matcher(prompt).find();
        if (producesArtifacts && scripts.isEmpty()) {
            reasons.add("artifact-producing workflow has no one-shot entry point under scripts/");
        }
        if (producesArtifacts && TEMPLATE_READ.matcher(prompt).find() && scripts.isEmpty()) {
            reasons.add(
                    "workflow requires reading a template before writing output, which exceeds one successful tool per turn");
        }
        if (definition.browserPreview() && definition.templateDir() != null && skillHome != null) {
            Path templateDir = skillHome.resolve(definition.templateDir()).normalize();
            if (!templateDir.startsWith(skillHome) || !Files.isDirectory(templateDir)) {
                reasons.add("declared template-dir does not resolve to a directory inside the skill home");
            }
        }

        return reasons.isEmpty() ? AdmissionDecision.allow() : AdmissionDecision.reject(reasons);
    }

    private Path resolveSkillHome(SkillDefinition definition, List<String> reasons) {
        if (definition.skillHome() == null || definition.skillHome().isBlank()) {
            reasons.add("SkillsJar has no extracted skill home");
            return null;
        }
        Path home = Path.of(definition.skillHome()).toAbsolutePath().normalize();
        if (!Files.isDirectory(home)) {
            reasons.add("extracted skill home does not exist: " + home);
            return null;
        }
        return home;
    }

    private List<Path> findScripts(Path skillHome) {
        if (skillHome == null) return List.of();
        Path scriptsDir = skillHome.resolve("scripts");
        if (!Files.isDirectory(scriptsDir)) return List.of();
        try (var paths = Files.list(scriptsDir)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (Exception _) {
            return List.of();
        }
    }

    public record AdmissionDecision(boolean accepted, List<String> reasons) {
        public AdmissionDecision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            if (accepted && !reasons.isEmpty()) {
                throw new IllegalArgumentException("Accepted admission decision cannot contain rejection reasons");
            }
        }

        static AdmissionDecision allow() {
            return new AdmissionDecision(true, List.of());
        }

        static AdmissionDecision reject(List<String> reasons) {
            return new AdmissionDecision(false, reasons);
        }
    }
}
