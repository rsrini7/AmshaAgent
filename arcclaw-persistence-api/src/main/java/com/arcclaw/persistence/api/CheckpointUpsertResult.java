package com.arcclaw.persistence.api;

/**
 * Result of an atomic find-or-create operation for a checkpoint.
 */
public record CheckpointUpsertResult(CheckpointDto checkpoint, boolean created) {}
