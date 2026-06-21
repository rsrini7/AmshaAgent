package com.arcclaw.core.domain.model;

public interface ReasoningStepRecord {

    Long getId();

    void setId(Long id);

    Long getCheckpointId();

    void setCheckpointId(Long checkpointId);

    int getSequenceNum();

    void setSequenceNum(int sequenceNum);

    String getThoughtJson();

    void setThoughtJson(String thoughtJson);

    String getAction();

    void setAction(String action);

    String getActionInputJson();

    void setActionInputJson(String actionInputJson);

    String getObservation();

    void setObservation(String observation);

    int getInputTokens();

    void setInputTokens(int inputTokens);

    int getOutputTokens();

    void setOutputTokens(int outputTokens);

    Boolean getSuccess();

    void setSuccess(Boolean success);

    String getErrorCode();

    void setErrorCode(String errorCode);

    String getTraceId();

    void setTraceId(String traceId);

    String getRootCauseClass();

    void setRootCauseClass(String rootCauseClass);
}
