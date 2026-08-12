package com.mindsafe.api.security;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SecuritySupport 认证上下文提取单点测试（S-011，审计 F1）
 * <p>
 * Controller 层原先 4 套写法（SecuritySupport / ChatController.extractContext /
 * AuthController 内联 instanceof / TeacherController 强转 principal）收敛为本组件。
 * 关键语义：缺失/类型不符 → BizException(UNAUTHORIZED) → 401，而非强转 500。
 */
class SecuritySupportTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final TenantContext ctx = new TenantContext(tenantId, userId, "student");

    private Authentication authWithDetails(Object details) {
        Authentication auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(details);
        return auth;
    }

    @Test
    @DisplayName("requireContext：details 为 TenantContext → 返回上下文")
    void requireContextHappyPath() {
        assertThat(SecuritySupport.requireContext(authWithDetails(ctx)))
                .isEqualTo(ctx);
    }

    @Test
    @DisplayName("requireContext：null 认证 → UNAUTHORIZED（而非 NPE/500）")
    void requireContextNullAuthThrows401() {
        assertThatThrownBy(() -> SecuritySupport.requireContext(null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("requireContext：details 非 TenantContext → UNAUTHORIZED（而非强转 ClassCastException/500）")
    void requireContextWrongDetailsTypeThrows401() {
        // 原 57 处无校验强转 principal 的写法在此类型不符时抛 ClassCastException → 500
        assertThatThrownBy(() -> SecuritySupport.requireContext(authWithDetails(new Object())))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThatThrownBy(() -> SecuritySupport.requireContext(authWithDetails("not-a-context")))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("requireUserId：正常路径返回 userId")
    void requireUserIdHappyPath() {
        assertThat(SecuritySupport.requireUserId(authWithDetails(ctx))).isEqualTo(userId);
    }

    @Test
    @DisplayName("requireUserId：缺失认证同样 401")
    void requireUserIdMissingThrows401() {
        assertThatThrownBy(() -> SecuritySupport.requireUserId(null))
                .isExactlyInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("提取的用户 ID 语义（R-018 details 通道）：返回 userId 而非 principal")
    void userIdSemantics() {
        // 语义锁定：details 通道统一取 userId，student 用户与 principal 无关
        TenantContext ctxWithPrincipal = new TenantContext(tenantId, userId, "student");
        assertThat(SecuritySupport.requireContext(authWithDetails(ctxWithPrincipal)).userId())
                .isEqualTo(userId);
    }
}
