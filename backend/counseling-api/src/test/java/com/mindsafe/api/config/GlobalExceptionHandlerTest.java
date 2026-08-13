package com.mindsafe.api.config;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 全局异常出口单测：BizException 状态码映射（ErrorCode/int）、
 * 参数校验/约束/非法参数/JSON 解析/缺参/类型不匹配/405/404/兜底 500 全覆盖。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BizException(ErrorCode)：映射枚举 httpStatus + code + message")
    void handleBizException_enum() {
        BizException e = new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBizException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
        assertThat(resp.getBody().message()).isEqualTo("用户不存在");
    }

    @Test
    @DisplayName("BizException(int 构造器)：无 ErrorCode 落 400")
    void handleBizException_int() {
        BizException e = new BizException(9999, "兼容错误码");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBizException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo(9999);
    }

    @Test
    @DisplayName("BizException(仅枚举)：message 取枚举默认文案")
    void handleBizException_defaultMessage() {
        BizException e = new BizException(ErrorCode.FORBIDDEN);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleBizException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().message()).isEqualTo(ErrorCode.FORBIDDEN.message());
    }

    @Test
    @DisplayName("MethodArgumentNotValidException：字段错误聚合")
    void handleValidation() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
        binding.addError(new FieldError("req", "phone", "手机号格式错误"));
        binding.addError(new FieldError("req", "code", "验证码必填"));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, binding);

        ApiResponse<Void> resp = handler.handleValidation(e);

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).contains("phone").contains("code");
    }

    @Test
    @DisplayName("ConstraintViolationException：错误信息透传")
    void handleConstraintViolation() {
        ConstraintViolationException e = new ConstraintViolationException("age must not be null", java.util.Set.of());

        ApiResponse<Void> resp = handler.handleConstraintViolation(e);

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).contains("age");
    }

    @Test
    @DisplayName("IllegalArgumentException：400 参数非法")
    void handleIllegalArgument() {
        ApiResponse<Void> resp = handler.handleIllegalArgument(new IllegalArgumentException("学生不存在"));

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).isEqualTo("学生不存在");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException：请求体格式错误 400")
    void handleUnreadable() {
        ApiResponse<Void> resp = handler.handleUnreadable(new HttpMessageNotReadableException("bad json"));

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).isEqualTo("请求体格式错误");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException：缺参提示参数名")
    void handleMissingParam() {
        MissingServletRequestParameterException e = new MissingServletRequestParameterException("phone", "string");

        ApiResponse<Void> resp = handler.handleMissingParam(e);

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).contains("phone");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException：类型不匹配提示期望类型")
    void handleTypeMismatch() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException("abc", Integer.class,
                "pageSize", null, null);

        ApiResponse<Void> resp = handler.handleTypeMismatch(e);

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).contains("pageSize").contains("Integer");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException：405")
    void handleMethodNotAllowed() {
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("DELETE");

        ApiResponse<Void> resp = handler.handleMethodNotAllowed(e);

        assertThat(resp.code()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(resp.message()).contains("DELETE");
    }

    @Test
    @DisplayName("NoResourceFoundException：404 资源不存在")
    void handleNoResource() {
        NoResourceFoundException e = new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/v1/x");

        ApiResponse<Void> resp = handler.handleNoResource(e);

        assertThat(resp.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
        assertThat(resp.message()).isEqualTo("资源不存在");
    }

    @Test
    @DisplayName("兜底 Exception：500 INTERNAL_ERROR")
    void handleUnexpected() {
        ApiResponse<Void> resp = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(resp.code()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    }
}
