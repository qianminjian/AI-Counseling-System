package com.mindsafe.api.config;

import com.mindsafe.service.billing.EntitlementChecker;

import java.util.List;
import java.util.Optional;

/**
 * 路径注册表单点（审计 F3）
 * <p>
 * 路径知识原先 4 处分散维护（SecurityConfig permitAll / EntitlementFilter.mapPathToFeature /
 * RateLimitInterceptor.resolveAction / WebMvcConfig addPathPatterns），新增/调整端点需同步多处，
 * 且豁免重叠无单测防漂移。本类为单一事实源，四消费方只读消费：
 * - {@link #PUBLIC_PATTERNS}：认证豁免（SecurityConfig permitAll）
 * - {@link #entitlementFeature(String)}：路径 → 功能权益（EntitlementFilter）
 * - {@link #rateLimitAction(String, String)}：方法+路径 → 限流动作（RateLimitInterceptor）
 * - {@link #RATE_LIMIT_PATH_PATTERNS}：限流拦截器注册范围（WebMvcConfig）
 * <p>
 * 注意：权益豁免（预警/SOS/危机，design/38 §4.2 硬编码不可覆盖）在 service 层
 * EntitlementChecker.isExempt 维护（冻结决策，不迁移）；本表只收敛"路径→策略"消费侧知识。
 */
public final class RouteCatalog {

    private RouteCatalog() {
    }

    // ─── 公开端点（认证豁免，无需 JWT；各端点防护措施注释见 doing/90 P3-3 公开端点审计）───
    public static final List<String> PUBLIC_PATTERNS = List.of(
            // 认证流程入口
            "/api/v1/auth/login",              // 登录入口（限流+锁定 LoginLockoutService）
            "/api/v1/auth/trial/register",     // 试注册（限流）
            "/api/v1/auth/pin-login",          // PIN 登录（限流）
            "/api/v1/auth/voice-login",        // 声纹登录（IP+embedding 双限流 SEC-007）
            "/api/v1/voiceprint/config",       // 声纹配置（只读）
            "/api/v1/voiceprint/verify",       // 声纹验证（IP+embedding 双限流+IP 300/min）
            "/api/v1/tts/login-prompt",        // TTS 登录引导（白名单文本，无需认证）
            "/api/v1/tts/personas",            // TTS 人设列表（登录前选音色/配置需要）
            "/api/v1/system/config",           // 系统配置（CFG-001 公开只读）
            "/api/v1/auth/refresh",            // token 刷新（需旧 token 签名校验）
            // 无屏终端设备（CFG-001 V39：设备端无 JWT，扫码入口匿名可查；绑定类端点需登录态）
            // P0-1/AD-002：report 通道暂回 permitAll（固件 HMAC 签名未就绪，见 frozen/73 §九）
            "/api/v1/device/report/**",
            "/api/v1/device/*/info",
            "/api/v1/device/*/status",
            "/api/v1/device/config/pull",
            // 企微 OAuth 回调（无 JWT，靠 code 换 token）
            "/api/v1/auth/wecom/**",
            // 家长端 API（内部 parentToken 验证，不走 Spring Security 角色）
            "/api/v1/parent/**",
            // toC 家庭版（doing/85 TOC-001：验证码注册/登录匿名，档案/设备等登录态）
            "/api/v1/toc/auth/send-code",
            "/api/v1/toc/auth/register",
            "/api/v1/toc/auth/login",
            // 平台管理员登录（ADMIN-P0-02：独立登录端点，R-8）
            "/api/v1/platform/auth/login",
            // 健康检查（Docker/Nginx 探针）
            "/actuator/health",
            // Prometheus 指标抓取（OPS-MON-003：容器 internal 网络内可达，不经公网）
            "/actuator/prometheus",
            // WebSocket（握手后内部鉴权）
            "/ws/**"
    );

    // ─── 限流拦截器注册范围（WebMvcConfig addPathPatterns 消费）───
    public static final List<String> RATE_LIMIT_PATH_PATTERNS = List.of(
            "/api/v1/chat/**",
            "/api/v1/tts/synthesize",
            "/api/v1/voiceprint/verify",
            "/api/v1/device/report/**",
            "/api/v1/device/config/pull"
    );

    /**
     * 路径 → 功能权益（EntitlementFilter 消费；仅映射受控路径，其余 empty 放行）。
     * 行为与原 EntitlementFilter.mapPathToFeature 逐条等价（startsWith 前缀匹配）。
     */
    public static Optional<String> entitlementFeature(String path) {
        if (path == null) {
            return Optional.empty();
        }
        if (path.startsWith("/api/v1/chat") || path.startsWith("/api/v1/conversations")) {
            return Optional.of(EntitlementChecker.FEAT_AI_CHAT);
        }
        if (path.startsWith("/api/v1/tts")) {
            return Optional.of(EntitlementChecker.FEAT_TTS);
        }
        if (path.startsWith("/api/v1/voice")) {
            return Optional.of(EntitlementChecker.FEAT_VOICE_INPUT);
        }
        if (path.startsWith("/api/v1/parent")) {
            return Optional.of(EntitlementChecker.FEAT_PARENT_H5);
        }
        if (path.startsWith("/api/v1/admin/export")) {
            return Optional.of(EntitlementChecker.FEAT_EXPORT);
        }
        if (path.startsWith("/api/v1/admin/dashboard")) {
            return Optional.of(EntitlementChecker.FEAT_DATA_DASHBOARD);
        }
        return Optional.empty();
    }

    /**
     * 方法 + URI → 限流动作（RateLimitInterceptor 消费；不限流 → empty）。
     * 行为与原 RateLimitInterceptor.resolveAction 逐条等价（contains 子串匹配 + POST 判定；
     * chat_message 优先于 create_session——含 /messages 的会话路径命中前者）。
     */
    public static Optional<String> rateLimitAction(String method, String uri) {
        if (uri == null || method == null) {
            return Optional.empty();
        }
        if (uri.contains("/chat/sessions/") && uri.contains("/messages")) {
            return Optional.of("chat_message");
        }
        if (uri.contains("/chat/sessions") && "POST".equalsIgnoreCase(method)) {
            return Optional.of("create_session");
        }
        if (uri.contains("/tts/synthesize")) {
            return Optional.of("tts_synthesize");
        }
        if (uri.contains("/voiceprint/verify")) {
            return Optional.of("voiceprint_verify");
        }
        if (uri.contains("/device/report/")) {
            return Optional.of("device_report");
        }
        if (uri.contains("/device/config/pull")) {
            return Optional.of("device_config_pull");
        }
        return Optional.empty();
    }
}
