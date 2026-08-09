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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：统一转为 ApiResponse 格式
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常（AUD-015：按 ErrorCode 映射 4xx/5xx，body 保留 code/message 兼容前端；
     * 网关/nginx 可按状态码告警重试，不再全部 200 承载）
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        HttpStatus status = resolveStatus(e.getCode());
        log.warn("业务异常: code={}, msg={}, httpStatus={}", e.getCode(), e.getMessage(), status.value());
        return ResponseEntity.status(status).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * AUD-015：错误码 → HTTP 状态码映射（按 ErrorCode 数值分段）。
     * 原则：客户端可纠正→4xx（参数/权限/冲突），资源终态→410/404，外部依赖故障→5xx。
     * 未知 code 落 400（业务错误，非服务故障）。
     */
    private static HttpStatus resolveStatus(int code) {
        if (code >= 60000) { // AI/LLM 60xxx
            return switch (code) {
                case 60001 -> HttpStatus.GATEWAY_TIMEOUT;        // LLM_TIMEOUT
                case 60002 -> HttpStatus.SERVICE_UNAVAILABLE;    // LLM_UNAVAILABLE
                case 60003 -> HttpStatus.BAD_REQUEST;            // LLM_CONTENT_BLOCKED
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        }
        if (code >= 40000) { // 风险/预警 40xxx
            return switch (code) {
                case 40001 -> HttpStatus.FORBIDDEN;        // RISK_ESCALATED（业务拦截）
                case 40002 -> HttpStatus.NOT_FOUND;        // ALERT_NOT_FOUND
                case 40003 -> HttpStatus.CONFLICT;         // ALERT_ALREADY_CLAIMED
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        if (code >= 30000) { // 对话/会话 30xxx
            return switch (code) {
                case 30001 -> HttpStatus.NOT_FOUND;            // SESSION_NOT_FOUND
                case 30002 -> HttpStatus.GONE;                 // SESSION_ENDED（终态）
                case 30003 -> HttpStatus.PAYLOAD_TOO_LARGE;    // MESSAGE_TOO_LONG
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        if (code >= 20000) { // 认证/授权 20xxx
            return switch (code) {
                case 20001 -> HttpStatus.UNAUTHORIZED;            // UNAUTHORIZED（前端 401 刷新/重登流程）
                case 20002, 20003, 20007, 20010 -> HttpStatus.FORBIDDEN; // 无权限/需监护人授权/需改密/密码过期
                case 20004, 20005, 20008, 20009 -> HttpStatus.BAD_REQUEST;
                case 20006 -> HttpStatus.CONFLICT;               // CONSENT_VERSION_MISMATCH
                case 20011 -> HttpStatus.GONE;                   // CONSENT_WITHDRAWN（同意撤回为资源终态，410 语义）
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        // 通用 10xxx
        return switch (code) {
            case 10001 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 10002 -> HttpStatus.BAD_REQUEST;
            case 10003, 10005 -> HttpStatus.NOT_FOUND;           // 资源/租户不存在
            case 10004 -> HttpStatus.TOO_MANY_REQUESTS;          // RATE_LIMITED
            case 10006 -> HttpStatus.GONE;                       // API_GONE（410 语义）
            default -> HttpStatus.BAD_REQUEST;
        };
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

    /** 请求体 JSON 解析失败（非法 JSON）→ 400，不再落兜底 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ApiResponse.error(ErrorCode.PARAM_INVALID.code(), "请求体格式错误");
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
