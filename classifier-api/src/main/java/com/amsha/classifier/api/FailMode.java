package com.amsha.classifier.api;

/**
 * Failure handling posture when a decision engine abstains or errors.
 */
public enum FailMode {
    FAIL_OPEN,
    FAIL_CLOSED
}
