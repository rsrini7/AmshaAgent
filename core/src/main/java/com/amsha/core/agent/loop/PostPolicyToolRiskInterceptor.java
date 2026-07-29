package com.amsha.core.agent.loop;

import com.amsha.core.classifier.ClassifierResult;

/**
 * Optional Phase 2.5 tool-risk SPI evaluated only after every deterministic guard allows a tool.
 * Lower priorities run first; implementations may only increase risk.
 */
public interface PostPolicyToolRiskInterceptor {

    int priority();

    ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context);
}
