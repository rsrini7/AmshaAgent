package com.arcclaw.core.compaction;

public class CompactionAbortedException extends RuntimeException {
    public CompactionAbortedException(String msg, Throwable cause) { super(msg, cause); }
}