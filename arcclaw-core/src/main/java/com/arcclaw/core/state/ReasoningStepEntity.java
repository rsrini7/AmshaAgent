package com.arcclaw.core.state;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for persisting individual reasoning steps.
 * <p>
 * This replaces the monolithic stateJson blob in Phase 55.4,
 * allowing for better querying and partial state loading.
 */
@Entity
@Table(name = "reasoning_step")
public class ReasoningStepEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(columnDefinition = "integer")
    private Long id;

    @Column(name = "checkpoint_id", nullable = false)
    private Long checkpointId;

    @Column(name = "sequence_num", nullable = false)
    private int sequenceNum;

    @Column(columnDefinition = "TEXT")
    private String thoughtJson;

    @Column(length = 128)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String actionInputJson;

    @Column(columnDefinition = "TEXT")
    private String observation;

    @Column(name = "input_tokens")
    private int inputTokens;

    @Column(name = "output_tokens")
    private int outputTokens;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(Long checkpointId) {
        this.checkpointId = checkpointId;
    }

    public int getSequenceNum() {
        return sequenceNum;
    }

    public void setSequenceNum(int sequenceNum) {
        this.sequenceNum = sequenceNum;
    }

    public String getThoughtJson() {
        return thoughtJson;
    }

    public void setThoughtJson(String thoughtJson) {
        this.thoughtJson = thoughtJson;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getActionInputJson() {
        return actionInputJson;
    }

    public void setActionInputJson(String actionInputJson) {
        this.actionInputJson = actionInputJson;
    }

    public String getObservation() {
        return observation;
    }

    public void setObservation(String observation) {
        this.observation = observation;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }
}
