package com.mindsafe.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一 API 响应体
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, Instant.now());
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "success", null, Instant.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), null, Instant.now());
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
