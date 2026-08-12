package com.mindsafe.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 审计 F6：统一序列化形状 {code,message,data,timestamp}。
     * 不加 {@code @JsonIgnore} 时 Jackson 会把本布尔 getter 序列化为额外 success 字段，
     * 前端 authFetch 被迫兼容多形状；Java 侧调用语义不受影响。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return code == 0;
    }
}
