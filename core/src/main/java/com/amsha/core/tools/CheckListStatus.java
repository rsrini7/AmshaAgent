package com.amsha.core.tools;

/**
 * Status of a checklist item.
 * Preserves exact lowercase wire-format names (pending, in_progress, completed).
 */
public enum CheckListStatus {
    pending,
    in_progress,
    completed
}
