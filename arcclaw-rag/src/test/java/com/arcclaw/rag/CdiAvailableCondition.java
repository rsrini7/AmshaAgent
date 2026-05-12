package com.arcclaw.rag;

import io.quarkus.arc.Arc;

public final class CdiAvailableCondition {

    private CdiAvailableCondition() {}

    public static boolean isCdiAvailable() {
        try {
            return Arc.container() != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
