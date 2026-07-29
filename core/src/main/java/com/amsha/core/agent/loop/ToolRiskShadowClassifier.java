package com.amsha.core.agent.loop;

import com.amsha.core.classifier.ShadowClassifier;

/** Optional tool-risk classifier used only after the authoritative guard allows a call. */
public interface ToolRiskShadowClassifier extends ShadowClassifier<ToolRiskShadowContext, ToolRiskPrediction> {}
