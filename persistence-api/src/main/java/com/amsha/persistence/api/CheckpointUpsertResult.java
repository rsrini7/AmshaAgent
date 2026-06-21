package com.amsha.persistence.api;

/**
 * Result of an atomic find-or-create operation for a checkpoint.
 */
public record CheckpointUpsertResult(CheckpointDto checkpoint, boolean created) {}
