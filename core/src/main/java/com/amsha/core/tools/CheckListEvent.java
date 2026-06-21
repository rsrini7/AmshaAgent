package com.amsha.core.tools;

/**
 * CDI event payload for checklist updates.
 *
 * @param sessionId The current session identification.
 * @param checkList The updated checklist data model.
 */
public record CheckListEvent(String sessionId, CheckListTool.CheckList checkList) {}
