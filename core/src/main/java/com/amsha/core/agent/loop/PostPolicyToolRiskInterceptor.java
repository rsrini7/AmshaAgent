package com.amsha.core.agent.loop;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ToolRiskAction;

/**
 * Optional tool-risk SPI evaluated only after every deterministic guard allows a tool.
 * Lower priorities run first; implementations may only increase risk.
 *
 * <p>Implementations run synchronously and in-process. They must not perform network I/O or log,
 * persist, export, metric-label, or retain sensitive context payloads.
 */
public interface PostPolicyToolRiskInterceptor {

    int priority();

    /** Evaluates one ephemeral context without retaining or externally transmitting its payload. */
    ClassifierResult<ToolRiskAction> evaluate(PostPolicyToolContext context);
}
