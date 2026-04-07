package com.arcclaw.core.compaction;

public class CompactionAbortedException extends RuntimeException {
    public CompactionAbortedException(String msg, Throwable cause) { super(msg, cause); }

    public CompactionAbortedException(String msg, String cause) { super(msg + ": " + cause); }
}