package com.mindsafe.api.config;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一转为 ApiResponse 格式
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /** 参数校验异常（@Valid 触发） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), detail);
    }

    /** 约束违反异常 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e) {
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), e.getMessage());
    }

    /** 非法参数（P1 审计修复：服务层 IllegalArgumentException（归属校验/学生不存在等）→ 400，不再 500） */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), e.getMessage());
    }

    /** 兜底：未预期异常 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR);
    }
}
