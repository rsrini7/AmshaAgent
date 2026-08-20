package com.amsha.core.tools;

import java.util.List;

/**
 * Immutable data record representing an agent execution checklist.
 *
 * @param checkList the list of checklist items (defensive copy)
 */
public record CheckList(List<CheckListItem> checkList) {

    public CheckList {
        checkList = (checkList == null) ? List.of() : List.copyOf(checkList);
    }
}
