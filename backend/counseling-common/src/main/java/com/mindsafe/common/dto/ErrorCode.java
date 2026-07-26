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
 */
public enum ErrorCode {

    // ===== 通用 10xxx =====
    SUCCESS(0, "success"),
    INTERNAL_ERROR(10001, "系统内部错误"),
    PARAM_INVALID(10002, "参数校验失败"),
    RESOURCE_NOT_FOUND(10003, "资源不存在"),
    RATE_LIMITED(10004, "请求过于频繁"),
    TENANT_NOT_FOUND(10005, "租户不存在或已停用"),

    // ===== 认证/授权 20xxx =====
    UNAUTHORIZED(20001, "未登录或 token 已过期"),
    FORBIDDEN(20002, "无权限访问"),
    CONSENT_REQUIRED(20003, "需要监护人授权"),
    INVITE_CODE_INVALID(20004, "邀请码无效或已过期"),
    INVITE_CODE_EXHAUSTED(20005, "邀请码已达使用上限"),
    CONSENT_VERSION_MISMATCH(20006, "告知同意版本不匹配，请重新同意"),
    PASSWORD_CHANGE_REQUIRED(20007, "首次登录需修改密码"),
    NICKNAME_INVALID(20008, "昵称不合规（2-12 字，不含敏感词）"),
    PASSWORD_POLICY_VIOLATION(20009, "密码不符合复杂度要求"),
    PASSWORD_EXPIRED(20010, "密码已过期，请修改后重试"),

    // ===== 对话/会话 30xxx =====
    SESSION_NOT_FOUND(30001, "会话不存在"),
    SESSION_ENDED(30002, "会话已结束"),
    MESSAGE_TOO_LONG(30003, "消息内容过长"),

    // ===== 风险/预警 40xxx =====
    RISK_ESCALATED(40001, "已触发风险预警"),
    ALERT_NOT_FOUND(40002, "预警记录不存在"),
    ALERT_ALREADY_CLAIMED(40003, "预警已被认领"),

    // ===== AI/LLM 60xxx =====
    LLM_TIMEOUT(60001, "AI 响应超时"),
    LLM_UNAVAILABLE(60002, "AI 服务暂不可用"),
    LLM_CONTENT_BLOCKED(60003, "内容被安全策略拦截");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
