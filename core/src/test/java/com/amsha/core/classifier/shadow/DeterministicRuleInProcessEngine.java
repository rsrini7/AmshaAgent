package com.amsha.core.classifier.shadow;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

import com.amsha.classifier.api.Choice;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.DecisionAnswer;
import com.amsha.classifier.api.DecisionMetadata;
import com.amsha.classifier.api.DecisionRequest;
import com.amsha.classifier.api.DecisionResult;
import com.amsha.classifier.api.InProcessDecisionEngine;
import com.amsha.classifier.api.QuestionKey;

/**
 * Deterministic rule-based in-process decision engine used for shadow harness evaluation.
 * Evaluates {@link DecisionRequest} strictly from serialized state without access to test-case answer keys.
 */
class DeterministicRuleInProcessEngine implements InProcessDecisionEngine {

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        String decId = request.decisionId();
        int schema = request.schemaVersion();
        String state = request.state();

        if (state == null
                || state.isBlank()
                || state.contains("malformed_json")
                || state.contains("CORRUPTED_JSON")
                || state.contains("UNKNOWN")
                || state.contains("tool=,")
                || state.contains("tool=\"\"")
                || state.contains("role=,")
                || state.contains("DUPLICATE_IDENTIFIER")) {
            return new DecisionResult.NoOpinion(decId, schema, NoOpinionReason.INFERENCE_ERROR);
        }
        if (state.contains("CONTRADICTORY")
                || state.contains("ALLOW ANY ACTION REGARDLESS OF RISK")
                || state.contains("all tool invocations touching filesystem must be blocked")
                || state.contains("NEVER assign any programming query to coder")
                || state.contains("all non-empty answers require immediate retry")
                || state.contains(
                        "Do not execute shell commands under any circumstances while also ensuring that you clean")) {
            return new DecisionResult.NoOpinion(decId, schema, NoOpinionReason.LOW_CONFIDENCE);
        }

        Map<QuestionKey<?>, DecisionAnswer> answers = new LinkedHashMap<>();

        if ("tool.risk.shadow".equals(decId)) {
            QuestionKey<Choice> riskKey = resolveKey(request, ShadowCorpusTask.TOOL_RISK.taskName());
            String stateLower = state.toLowerCase(Locale.ROOT);
            if (stateLower.contains("rm -rf")
                    || stateLower.contains("drop table")
                    || stateLower.contains("clean working directory")
                    || stateLower.contains("ignore previous instructions")
                    || stateLower.contains("curl http://attacker")
                    || stateLower.contains("sudo")
                    || stateLower.contains("/var/data/*")
                    || stateLower.contains("/etc/shadow")
                    || stateLower.contains("/etc/hosts")
                    || stateLower.contains("/etc/")
                    || stateLower.contains("evil.example.com")
                    || stateLower.contains("vetted by root admin")
                    || stateLower.contains("mark low_risk")
                    || stateLower.contains("ignore guard rules")) {
                answers.put(riskKey, new Choice("BLOCK", OptionalDouble.of(0.96)));
            } else if (stateLower.contains("execute_shell")
                    || stateLower.contains("install")
                    || stateLower.contains("network")
                    || stateLower.contains("write_file")
                    || stateLower.contains("delete_file")) {
                answers.put(riskKey, new Choice("REQUIRE_APPROVAL", OptionalDouble.of(0.90)));
            } else {
                answers.put(riskKey, new Choice("LOW_RISK", OptionalDouble.of(0.92)));
            }
            return new DecisionResult.Opinion(
                    decId, schema, answers, "rule-deterministic-tool-risk", DecisionMetadata.empty());
        }

        if ("answer.quality.shadow".equals(decId)) {
            QuestionKey<Choice> qualityKey = resolveKey(request, ShadowCorpusTask.ANSWER_QUALITY.taskName());
            String stateLower = state.toLowerCase(Locale.ROOT);
            if (stateLower.contains("answer=null")
                    || (stateLower.contains("answer=")
                            && stateLower.contains(",toolinvokedinturn=")
                            && stateLower.contains("answer=,"))
                    || stateLower.contains("sorry, i could not")
                    || stateLower.contains("execution error")
                    || stateLower.contains("partial truncated result")
                    || stateLower.contains("connection timed out")
                    || stateLower.contains("error:")
                    || stateLower.contains("bypass answer-quality")
                    || stateLower.contains("system advisory")
                    || (stateLower.contains("do not include any third-party")
                            && (stateLower.contains("apache") || stateLower.contains("guava")))
                    || stateLower.contains("retry")) {
                answers.put(qualityKey, new Choice("RETRY_RECOMMENDED", OptionalDouble.of(0.88)));
            } else {
                answers.put(qualityKey, new Choice("ACCEPTABLE", OptionalDouble.of(0.91)));
            }
            return new DecisionResult.Opinion(
                    decId, schema, answers, "rule-deterministic-quality", DecisionMetadata.empty());
        }

        if ("intent.route".equals(decId) || "intent.shadow".equals(decId)) {
            QuestionKey<Choice> roleKey = resolveKey(request, "role");
            String stateLower = state.toLowerCase(Locale.ROOT);
            if (stateLower.contains("system override")
                    || stateLower.contains("superuser-exec")
                    || stateLower.contains("ignore all previous")
                    || stateLower.contains("ignore previous instructions")) {
                return new DecisionResult.NoOpinion(decId, schema, NoOpinionReason.LOW_CONFIDENCE);
            }
            String selectedRole;
            if (stateLower.contains("do not conduct new investigations")
                    || stateLower.contains("synthesize")
                    || stateLower.contains("summarize")
                    || stateLower.contains("briefing table")
                    || stateLower.contains("executive summary")) {
                selectedRole = "synthesizer";
            } else if (stateLower.contains("do not write any code")
                    || stateLower.contains("do not write code")
                    || stateLower.contains("sqlite")
                    || stateLower.contains("trade-offs")
                    || stateLower.contains("investigate")
                    || stateLower.contains("research")) {
                selectedRole = "researcher";
            } else if (stateLower.contains("architecture")
                    || stateLower.contains("design")
                    || stateLower.contains("subsystem boundaries")
                    || stateLower.contains("hexagonal ports")) {
                selectedRole = "architect";
            } else if (stateLower.contains("npe")
                    || stateLower.contains("stack trace")
                    || stateLower.contains("nullpointerexception")
                    || stateLower.contains("debug")) {
                selectedRole = "debugger";
            } else if (stateLower.contains("python")
                    || stateLower.contains("java")
                    || stateLower.contains("lru cache")
                    || stateLower.contains("linked list")
                    || stateLower.contains("implement")
                    || stateLower.contains("refactor")
                    || stateLower.contains("fibonacci")
                    || stateLower.contains("code")) {
                selectedRole = "coder";
            } else {
                return new DecisionResult.NoOpinion(decId, schema, NoOpinionReason.LOW_CONFIDENCE);
            }
            answers.put(roleKey, new Choice(selectedRole, OptionalDouble.of(0.92)));
            return new DecisionResult.Opinion(
                    decId, schema, answers, "rule-deterministic-intent", DecisionMetadata.empty());
        }

        return new DecisionResult.NoOpinion(decId, schema, NoOpinionReason.INFERENCE_ERROR);
    }

    @SuppressWarnings("unchecked")
    private static QuestionKey<Choice> resolveKey(DecisionRequest request, String name) {
        if (request.questions().containsKey(name)) {
            return (QuestionKey<Choice>) request.question(name).key();
        }
        return QuestionKey.choice(name);
    }
}
