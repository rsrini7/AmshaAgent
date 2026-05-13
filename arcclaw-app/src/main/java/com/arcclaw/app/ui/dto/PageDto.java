package com.arcclaw.app.ui.dto;

import java.util.List;

public record PageDto<T>(
    List<T> items,
    int page,
    int size,
    long totalCount,
    int totalPages
) {
    public static <T> PageDto<T> of(List<T> items, int page, int size, long totalCount) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalCount / size) : 0;
        return new PageDto<>(items, page, size, totalCount, totalPages);
    }
}
