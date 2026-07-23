package com.mindsafe.common.dto;

import java.util.List;

/**
 * 分页响应体
 */
public record PageResponse<T>(
        List<T> items,
        long total,
        int page,
        int size
) {
    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }

    public int totalPages() {
        return size > 0 ? (int) Math.ceil((double) total / size) : 0;
    }

    public boolean hasNext() {
        return page < totalPages();
    }
}
