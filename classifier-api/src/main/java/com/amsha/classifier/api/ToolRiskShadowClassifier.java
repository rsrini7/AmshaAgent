package com.amsha.classifier.api;

/**
 * Optional in-process tool-risk classifier used only after the authoritative guard allows a call.
 * Implementations must not perform network I/O or log, persist, export, metric-label, or retain
 * context payloads.
 */
public interface ToolRiskShadowClassifier extends ShadowClassifier<ToolRiskShadowContext, ToolRiskPrediction> {}
