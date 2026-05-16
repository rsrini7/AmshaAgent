package com.arcclaw.core.compaction;

import com.arcclaw.core.exception.ArcClawException;

public class CompactionAbortedException extends ArcClawException {
    public CompactionAbortedException(String msg, Throwable cause) { super(msg, cause); }

    public CompactionAbortedException(String msg, String cause) { super(msg + ": " + cause); }
}