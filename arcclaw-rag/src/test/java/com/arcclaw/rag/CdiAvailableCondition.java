package com.arcclaw.rag;

public final class CdiAvailableCondition {

    private CdiAvailableCondition() {}

    public static boolean isCdiAvailable() {
        try {
            return io.quarkus.arc.Arc.container() != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
