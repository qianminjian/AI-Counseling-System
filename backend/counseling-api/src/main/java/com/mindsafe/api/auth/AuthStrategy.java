package com.mindsafe.api.auth;

/**
 * 可插拔认证策略端口（开闭原则）
 * <p>
 * 试用 / 学校 / 微信 / 手机号都是不同实现，新增认证方式不改动核心契约。
 * <ul>
 *   <li>{@code TrialAuthStrategy} — 本期：邀请码 + 昵称 + 年龄 + 同意</li>
 *   <li>SchoolPasswordAuthStrategy — 现有 login 逻辑（本期不重构迁入）</li>
 *   <li>WechatAuthStrategy / PhoneAuthStrategy — 正式期（YAGNI 暂不做）</li>
 * </ul>
 */
public interface AuthStrategy {

    /** 策略标识（trial / school / wechat / phone） */
    String type();

    /**
     * 认证并返回统一身份；失败抛 BizException(UNAUTHORIZED / 具体错误码)
     *
     * @param request 认证请求（不同策略有不同字段，由策略自行解析）
     * @return 认证成功的统一身份
     */
    AuthenticatedUser authenticate(Object request);
}
