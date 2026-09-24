package com.amsha.core.agent.loop;

import java.util.OptionalDouble;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskDecisionPolicy.PolicyResolution;

final class PostPolicyToolRiskTestSupport {
    private PostPolicyToolRiskTestSupport() {}

    static PolicyResolution resolution(ClassifierResult<ToolRiskAction> result) {
        return switch (result) {
            case ClassifierResult.Opinion<ToolRiskAction> opinion ->
                new PolicyResolution(
                        PostPolicyToolRiskDispatcher.DECISION_ID,
                        PostPolicyToolRiskDispatcher.SCHEMA_VERSION,
                        opinion.value(),
                        OptionalDouble.of(opinion.confidence()),
                        false,
                        false,
                        opinion.ruleId());
            case ClassifierResult.NoOpinion<ToolRiskAction> noOpinion ->
                new PolicyResolution(
                        PostPolicyToolRiskDispatcher.DECISION_ID,
                        PostPolicyToolRiskDispatcher.SCHEMA_VERSION,
                        null,
                        OptionalDouble.empty(),
                        false,
                        true,
                        noOpinion.reason().name());
        };
    }
}
