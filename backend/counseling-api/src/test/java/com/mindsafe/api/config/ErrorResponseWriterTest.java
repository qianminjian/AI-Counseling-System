package com.mindsafe.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindsafe.common.dto.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErrorResponseWriter 统一错误序列化出口测试（审计 F6 单点化）
 * <p>
 * 非 controller 路径原先手拼 4 套形状 JSON（success/data 字段不一），现统一按
 * ApiResponse 契约 {code,message,data,timestamp} 序列化。本测试锁定：
 * 1. HTTP 状态码与响应头正确写回；
 * 2. JSON 形状统一（无 success 字段、无 data 字段、含 timestamp）；
 * 3. message 含引号/emoji 时经 Jackson 转义，不破 JSON（杜绝手拼字符串事故同源模式）。
 */
class ErrorResponseWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private StringWriter writeThrough(HttpServletResponse response) throws Exception {
        StringWriter buffer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(buffer));
        return buffer;
    }

    @Test
    @DisplayName("按枚举错误码写回：401 + 自定义 message，形状统一")
    void writeByErrorCodeShape() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter buffer = writeThrough(response);

        ErrorResponseWriter.write(response, 401, ErrorCode.UNAUTHORIZED, "未登录或 token 已过期");

        verify(response).setStatus(401);
        verify(response).setContentType("application/json;charset=UTF-8");

        JsonNode json = mapper.readTree(buffer.toString());
        assertThat(json.get("code").asInt()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(json.get("message").asText()).isEqualTo("未登录或 token 已过期");
        assertThat(json.has("success")).as("F6：统一形状不含 success 字段").isFalse();
        assertThat(json.has("data")).as("F6：data 为 null 时省略（@JsonInclude NON_NULL）").isFalse();
        assertThat(json.has("timestamp")).as("F6：统一形状含 timestamp").isTrue();
    }

    @Test
    @DisplayName("按原始 code 写回：429 限流（非枚举码场景）")
    void writeByRawCode() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter buffer = writeThrough(response);

        ErrorResponseWriter.write(response, 429, 429, "操作太频繁了");

        verify(response).setStatus(429);
        JsonNode json = mapper.readTree(buffer.toString());
        assertThat(json.get("code").asInt()).isEqualTo(429);
        assertThat(json.get("message").asText()).isEqualTo("操作太频繁了");
        assertThat(json.has("success")).isFalse();
        assertThat(json.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("message 含引号/emoji：Jackson 转义后 JSON 合法，内容不丢失")
    void messageWithQuotesAndEmojiStaysValidJson() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter buffer = writeThrough(response);
        String trickyMessage = "他说 \"你好\" 🚀 emoji";

        ErrorResponseWriter.write(response, 403, 403, trickyMessage);

        // 写回的原始字节必须是合法 JSON（可被 readTree 解析 = 未破 JSON）
        JsonNode json = mapper.readTree(buffer.toString());
        assertThat(json.get("message").asText()).isEqualTo(trickyMessage);
    }

    @Test
    @DisplayName("写回内容不含手拼字符串痕迹（统一走 ObjectMapper 序列化）")
    void noHandAssembledJson() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter buffer = writeThrough(response);

        ErrorResponseWriter.write(response, 401, ErrorCode.UNAUTHORIZED, "未登录或 token 已过期");
        String raw = buffer.toString();

        // 手拼 JSON 常见事故：字符串拼接的 success 键、未转义 message、缺 timestamp
        assertThat(raw).doesNotContain("\"success\":");
        assertThat(raw).doesNotContain("+");
        assertThat(raw).contains("\"timestamp\"");
    }
}
