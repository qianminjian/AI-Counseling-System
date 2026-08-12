package com.mindsafe.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 统一错误响应序列化出口（审计 F6）
 * <p>
 * 非 controller 路径原先手拼 4 套形状 JSON（Security entryPoint/deniedHandler 带 success 字段、
 * 限流拦截带 data、权益拦截缺 data/timestamp），前端 authFetch 被迫兼容多形状。
 * 统一经本工具按 ApiResponse 契约 {code,message,data,timestamp} 序列化写回（Jackson 自动转义，
 * 不再手拼字符串——message 含引号/emoji 不再破 JSON）。
 */
public final class ErrorResponseWriter {

    // timestamp 为 Instant：必须注册 JSR310 模块，并禁用 epoch 秒（对齐 Spring Boot 主 ObjectMapper 的 ISO-8601 输出）
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ErrorResponseWriter() {
    }

    /** 按枚举错误码写回（自定义 message 覆盖默认文案） */
    public static void write(HttpServletResponse response, int httpStatus, ErrorCode errorCode, String message)
            throws IOException {
        write(response, httpStatus, errorCode.code(), message);
    }

    /** 按原始 code/message 写回（限流 429 / 权益拦截等非枚举码场景） */
    public static void write(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(ApiResponse.error(code, message)));
    }
}
