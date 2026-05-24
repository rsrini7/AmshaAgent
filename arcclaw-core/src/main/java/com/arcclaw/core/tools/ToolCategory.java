package com.arcclaw.core.tools;

public enum ToolCategory {

    NETWORK("network-tool-executor", 8, 20),
    COMPUTE("compute-tool-executor", 4, 10),
    DATABASE("database-tool-executor", 6, 20),
    INTERNAL("internal-tool-executor", 20, 100);

    private final String executorName;
    private final int defaultMaxAsync;
    private final int defaultMaxQueued;

    ToolCategory(String executorName, int defaultMaxAsync, int defaultMaxQueued) {
        this.executorName = executorName;
        this.defaultMaxAsync = defaultMaxAsync;
        this.defaultMaxQueued = defaultMaxQueued;
    }

    public String executorName() {
        return executorName;
    }

    public int defaultMaxAsync() {
        return defaultMaxAsync;
    }

    public int defaultMaxQueued() {
        return defaultMaxQueued;
    }
}
