package com.amsha.app.classifier;

/** Test fixture variant that simulates a provider projection which omits tool operations. */
public class DecisionProviderMissingOperationFixture extends DecisionProviderFixture {
    @Override
    protected String projectionAllowList() {
        return "user_message";
    }
}
