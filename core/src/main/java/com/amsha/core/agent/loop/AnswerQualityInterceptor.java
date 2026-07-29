package com.amsha.core.agent.loop;

import com.amsha.core.classifier.ClassifierResult;

/** Optional ordered Phase 2.5 SPI evaluated only after authoritative answer acceptance. */
public interface AnswerQualityInterceptor {

    int priority();

    ClassifierResult<AnswerQualityAction> evaluate(AnswerQualityContext context);
}
