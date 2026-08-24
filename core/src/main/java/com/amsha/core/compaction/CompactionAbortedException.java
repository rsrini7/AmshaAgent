package com.amsha.core.compaction;

import com.amsha.common.exception.AmshaException;

public class CompactionAbortedException extends AmshaException {
    public CompactionAbortedException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public CompactionAbortedException(String msg, String cause) {
        super(msg + ": " + cause);
    }
}
