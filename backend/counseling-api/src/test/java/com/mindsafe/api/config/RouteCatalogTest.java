package com.mindsafe.api.config;

import com.mindsafe.service.billing.EntitlementChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RouteCatalog 路径注册表测试（审计 F3 单点化）
 * <p>
 * 路径知识 4 处分散（SecurityConfig permitAll / EntitlementFilter.mapPathToFeature /
 * RateLimitInterceptor.resolveAction / WebMvcConfig addPathPatterns）收敛为单一事实源。
 * 本测试以表驱动锁定"路径 → 策略"映射，防止新增/调整端点时漂移。
 */
class RouteCatalogTest {

    @Nested
    @DisplayName("entitlementFeature：路径 → 功能权益（原 EntitlementFilter.mapPathToFeature）")
    class EntitlementFeature {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "/api/v1/chat/send,ai_chat",
                "/api/v1/chat/sessions/abc/messages,ai_chat",
                "/api/v1/conversations/abc,ai_chat",
                "/api/v1/tts/synthesize,tts",
                "/api/v1/tts/personas,tts",
                "/api/v1/voiceprint/verify,voice_input",
                "/api/v1/voiceprint/config,voice_input",
                "/api/v1/parent/children,parent_h5",
                "/api/v1/admin/export/alerts,export",
                "/api/v1/admin/dashboard/summary,data_dashboard"
        })
        void mapsKnownPaths(String path, String expectedFeature) {
            assertThat(RouteCatalog.entitlementFeature(path))
                    .isEqualTo(Optional.of(expectedFeature));
        }

        @ParameterizedTest(name = "{0} → empty（放行，不参与权益拦截）")
        @CsvSource({
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/system/config",
                "/actuator/health",
                "/ws/alert",
                "/api/v1/device/report/1"
        })
        void unmappedPathsPassThrough(String path) {
            assertThat(RouteCatalog.entitlementFeature(path)).isEmpty();
        }

        @Test
        @DisplayName("null 路径 → empty，不抛异常")
        void nullPathIsEmpty() {
            assertThat(RouteCatalog.entitlementFeature(null)).isEmpty();
        }

        @Test
        @DisplayName("权益豁免不迁移（冻结决策）：chat 前缀仍映射 ai_chat")
        void chatStillMapsToAiChat() {
            // 冻结决策（doing/38 §4.2）：预警/SOS 豁免在 service 层 EntitlementChecker.isExempt 硬编码，
            // 本表只收敛消费侧"路径→功能"知识，映射关系不变
            assertThat(RouteCatalog.entitlementFeature("/api/v1/chat/send"))
                    .contains(EntitlementChecker.FEAT_AI_CHAT);
        }
    }

    @Nested
    @DisplayName("rateLimitAction：方法+路径 → 限流动作（原 RateLimitInterceptor.resolveAction）")
    class RateLimitAction {

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
                "POST,/api/v1/chat/sessions,create_session",
                "POST,/api/v1/chat/sessions/abc/messages,chat_message",
                "GET,/api/v1/chat/sessions/abc/messages,chat_message",
                "GET,/api/v1/tts/synthesize,tts_synthesize",
                "POST,/api/v1/voiceprint/verify,voiceprint_verify",
                "GET,/api/v1/device/report/2026-01-01,device_report",
                "POST,/api/v1/device/config/pull,device_config_pull"
        })
        void mapsKnownRequests(String method, String uri, String expectedAction) {
            assertThat(RouteCatalog.rateLimitAction(method, uri))
                    .isEqualTo(Optional.of(expectedAction));
        }

        @Test
        @DisplayName("chat_message 优先于 create_session（含 /messages 的会话路径）")
        void chatMessageWinsOverCreateSession() {
            assertThat(RouteCatalog.rateLimitAction("POST", "/api/v1/chat/sessions/abc/messages"))
                    .contains("chat_message");
        }

        @Test
        @DisplayName("会话路径仅 POST 计 create_session（GET 列表/详情不限流）")
        void createSessionRequiresPost() {
            assertThat(RouteCatalog.rateLimitAction("GET", "/api/v1/chat/sessions")).isEmpty();
            assertThat(RouteCatalog.rateLimitAction("GET", "/api/v1/chat/sessions/abc")).isEmpty();
        }

        @ParameterizedTest(name = "{0} {1} → empty（不限流）")
        @CsvSource({
                "GET,/api/v1/auth/login",
                "POST,/api/v1/auth/login",
                "GET,/api/v1/system/config",
                "GET,/api/v1/parent/children"
        })
        void unmappedRequestsPassThrough(String method, String uri) {
            assertThat(RouteCatalog.rateLimitAction(method, uri)).isEmpty();
        }

        @Test
        @DisplayName("null 参数 → empty，不抛异常")
        void nullArgsAreEmpty() {
            assertThat(RouteCatalog.rateLimitAction(null, "/api/v1/chat/sessions")).isEmpty();
            assertThat(RouteCatalog.rateLimitAction("POST", null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("路径清单常量（SecurityConfig permitAll / WebMvcConfig addPathPatterns 消费）")
    class PathLists {

        @Test
        @DisplayName("PUBLIC_PATTERNS 覆盖认证豁免关键入口")
        void publicPatternsCoverKeyEntries() {
            assertThat(RouteCatalog.PUBLIC_PATTERNS).contains(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/voice-login",
                    "/api/v1/device/report/**",
                    "/api/v1/parent/**",
                    "/api/v1/platform/auth/login",
                    "/ws/**",
                    "/actuator/health"
            );
        }

        @Test
        @DisplayName("RATE_LIMIT_PATH_PATTERNS 覆盖限流注册范围")
        void rateLimitPatternsCoverRegistrationScope() {
            assertThat(RouteCatalog.RATE_LIMIT_PATH_PATTERNS).contains(
                    "/api/v1/chat/**",
                    "/api/v1/tts/synthesize",
                    "/api/v1/voiceprint/verify",
                    "/api/v1/device/report/**",
                    "/api/v1/device/config/pull"
            );
        }
    }
}
