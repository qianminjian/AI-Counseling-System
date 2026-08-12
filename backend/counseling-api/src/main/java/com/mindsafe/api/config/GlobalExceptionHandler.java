package com.mindsafe.api.config;

import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * 全局异常处理器：统一转为 ApiResponse 格式
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        // 审计 F7：状态码映射单点收敛到 ErrorCode.httpStatus（编译期强制），魔法 switch 已删除
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus status = errorCode != null
                ? HttpStatus.valueOf(errorCode.httpStatus())
                // int 构造器兼容（当前无调用方）：未知 code 落 400（业务错误，非服务故障），与原 resolveStatus 兜底语义一致
                : HttpStatus.BAD_REQUEST;
        log.warn("业务异常: code={}, msg={}, httpStatus={}", e.getCode(), e.getMessage(), status.value());
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
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

    /** 非法参数（P1 审计修复：服务层 IllegalArgumentException（归属校验/学 生不存在等）→ 400，不再 500；AD-007 收编 DeviceController.wrap/TocDeviceController 手写 catch 后为唯一出口） */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), e.getMessage());
    }

    /** 请求体 JSON 解析失败（非法 JSON）→ 400，不再落兜底 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "请求体格式错误");
    }

    /** 必填请求参数缺失（BUG-T-09-01，UI-TEST-013）→ 400，不再落兜底 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "缺少请求参数: " + e.getParameterName());
    }

    /** 请求参数类型转换失败（如数字传入 UUID 参数）→ 400，不再落兜底 500 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(),
                "参数 " + e.getName() + " 格式不正确（期望 " + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知") + "）");
    }

    /** HTTP 方法不允许（如对只读端点发写请求）→ 405，不再落兜底 500 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("方法不允许: method={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "请求方法不允许: " + e.getMethod());
    }

    /** 未知路径（Spring Boot 3.2+ NoResourceFoundException）→ 404，不再落兜底 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND.code(), "资源不存在");
    }

    /** 兜底：未预期异常 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR);
    }
}
