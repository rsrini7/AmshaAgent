package com.amsha.core.agent.loop;

import com.amsha.classifier.api.AnswerQualityAction;
import com.amsha.classifier.api.AnswerQualityContext;
import com.amsha.classifier.api.ClassifierResult;

/**
 * Optional ordered SPI evaluated only after authoritative answer acceptance.
 *
 * <p>Implementations run synchronously and in-process. They must not perform network I/O or log,
 * persist, export, metric-label, or retain sensitive context payloads.
 */
public interface AnswerQualityInterceptor {

    int priority();

    /** Evaluates one ephemeral context without retaining or externally transmitting its payload. */
    ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context);
}
