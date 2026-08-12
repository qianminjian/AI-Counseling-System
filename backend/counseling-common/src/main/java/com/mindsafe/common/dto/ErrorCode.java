package com.mindsafe.common.dto;

/**
 * 错误码定义（按模块分段）
 * <p>
 * 分段规则：
 * - 10xxx: 通用/系统错误
 * - 20xxx: 认证/授权
 * - 30xxx: 对话/会话
 * - 40xxx: 风险/预警
 * - 50xxx: 个案管理
 * - 60xxx: AI/LLM 调用
 * <p>
 * 错误码 → HTTP 状态映射单点（审计 F7）：每个枚举携带 httpStatus，编译期强制——
 * 新增错误码必须同时定义状态码，GlobalExceptionHandler 直接消费，不再维护魔法 switch。
 */
public enum ErrorCode {

    // ===== 通用 10xxx =====
    SUCCESS(0, "success", 200),
    INTERNAL_ERROR(10001, "系统内部错误", 500),
    PARAM_INVALID(10002, "参数校验失败", 400),
    RESOURCE_NOT_FOUND(10003, "资源不存在", 404),
    RATE_LIMITED(10004, "请求过于频繁", 429),
    TENANT_NOT_FOUND(10005, "租户不存在或已停用", 404),
    API_GONE(10006, "接口已下线", 410),

    // ===== 认证/授权 20xxx =====
    UNAUTHORIZED(20001, "未登录或 token 已过期", 401),
    FORBIDDEN(20002, "无权限访问", 403),
    CONSENT_REQUIRED(20003, "需要监护人授权", 403),
    INVITE_CODE_INVALID(20004, "邀请码无效或已过期", 400),
    INVITE_CODE_EXHAUSTED(20005, "邀请码已达使用上限", 400),
    CONSENT_VERSION_MISMATCH(20006, "告知同意版本不匹配，请重新同意", 409),
    PASSWORD_CHANGE_REQUIRED(20007, "首次登录需修改密码", 403),
    NICKNAME_INVALID(20008, "昵称不合规（2-12 字，不含敏感词）", 400),
    PASSWORD_POLICY_VIOLATION(20009, "密码不符合复杂度要求", 400),
    PASSWORD_EXPIRED(20010, "密码已过期，请修改后重试", 403),
    CONSENT_WITHDRAWN(20011, "监护人同意已撤回，链接已失效", 410),

    // ===== 对话/会话 30xxx =====
    SESSION_NOT_FOUND(30001, "会话不存在", 404),
    SESSION_ENDED(30002, "会话已结束", 410),
    MESSAGE_TOO_LONG(30003, "消息内容过长", 413),

    // ===== 风险/预警 40xxx =====
    RISK_ESCALATED(40001, "已触发风险预警", 403),
    ALERT_NOT_FOUND(40002, "预警记录不存在", 404),
    ALERT_ALREADY_CLAIMED(40003, "预警已被认领", 409),

    // ===== AI/LLM 60xxx =====
    LLM_TIMEOUT(60001, "AI 响应超时", 504),
    LLM_UNAVAILABLE(60002, "AI 服务暂不可用", 503),
    LLM_CONTENT_BLOCKED(60003, "内容被安全策略拦截", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** HTTP 状态码（GlobalExceptionHandler 消费；新增错误码必须显式定义，编译期强制） */
    public int httpStatus() {
        return httpStatus;
    }
}
