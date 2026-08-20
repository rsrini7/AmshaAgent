package com.amsha.core.tools;

/**
 * Immutable data record representing a single item in a checklist.
 *
 * @param content the content or description of the checklist item
 * @param status the status of the item
 * @param activeForm the active form representation if present
 */
public record CheckListItem(String content, CheckListStatus status, String activeForm) {}
