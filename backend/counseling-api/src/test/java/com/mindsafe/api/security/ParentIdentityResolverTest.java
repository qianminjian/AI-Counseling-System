package com.mindsafe.api.security;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.consent.ConsentWithdrawalService;
import com.mindsafe.service.parent.ParentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ParentIdentityResolver 单测（doing/89 N-003 AC-89-04，2026-08-11）
 * 覆盖：统一 token 校验（签名/refresh/声纹/userType）、旧链接语义（withdrawn 410）、
 * 新登录语义、绑定校验、宽松路径（requireLinkedOnly）。
 */
class ParentIdentityResolverTest {

    private JwtTokenProvider jwtTokenProvider;
    private ParentService parentService;
    private ParentIdentityResolver resolver;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID parentId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private static final String TOKEN = "parent-token";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        parentService = mock(ParentService.class);
        resolver = new ParentIdentityResolver(jwtTokenProvider, parentService);

        // F2：单次 parse 语义——默认返回合法 parent access 快照
        when(jwtTokenProvider.parseOrNull(TOKEN)).thenReturn(
                new JwtTokenProvider.ParsedToken("jti-1", parentId, "parent", tenantId, TokenType.ACCESS));
    }

    private void stubParent(String userType, TokenType type, UUID sub) {
        when(jwtTokenProvider.parseOrNull(TOKEN)).thenReturn(
                new JwtTokenProvider.ParsedToken("jti-1", sub, userType, tenantId, type));
    }

    @Test
    @DisplayName("统一校验：无效签名 → UNAUTHORIZED")
    void invalidTokenRejected() {
        when(jwtTokenProvider.parseOrNull(TOKEN)).thenReturn(null);
        assertThatThrownBy(() -> resolver.resolveLoginIdentity("Bearer " + TOKEN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("统一校验：refresh 凭证 → UNAUTHORIZED")
    void refreshTokenRejected() {
        stubParent("parent", TokenType.REFRESH, parentId);
        assertThatThrownBy(() -> resolver.resolveLoginIdentity("Bearer " + TOKEN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("统一校验：声纹凭证 → UNAUTHORIZED")
    void voiceCredentialRejected() {
        stubParent("parent", TokenType.VOICE_CREDENTIAL, parentId);
        assertThatThrownBy(() -> resolver.resolveLegacyLink("Bearer " + TOKEN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("统一校验：非 parent userType → UNAUTHORIZED")
    void studentTokenRejected() {
        stubParent("student", TokenType.ACCESS, parentId);
        assertThatThrownBy(() -> resolver.resolveLoginIdentity("Bearer " + TOKEN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("新登录语义：sub=parentId → ParentIdentity")
    void resolveLoginIdentityOk() {
        var identity = resolver.resolveLoginIdentity("Bearer " + TOKEN);
        assertThat(identity.parentId()).isEqualTo(parentId);
        assertThat(identity.tenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("旧链接语义：学生存在 + 未撤回 → ParentLinkIdentity")
    void resolveLegacyLinkOk() {
        stubParent("parent", TokenType.PARENT_REPORT, studentUserId);
        User student = new User();
        student.setStatus("active");
        when(parentService.getStudent(tenantId, studentUserId)).thenReturn(student);

        var info = resolver.resolveLegacyLink("Bearer " + TOKEN);
        assertThat(info.studentUserId()).isEqualTo(studentUserId);
    }

    @Test
    @DisplayName("旧链接语义：withdrawn → CONSENT_WITHDRAWN（410，AC-89-03/04）")
    void legacyLinkWithdrawnRejected() {
        stubParent("parent", TokenType.PARENT_REPORT, studentUserId);
        User student = new User();
        student.setStatus(ConsentWithdrawalService.STATUS_WITHDRAWN);
        when(parentService.getStudent(tenantId, studentUserId)).thenReturn(student);

        assertThatThrownBy(() -> resolver.resolveLegacyLink("Bearer " + TOKEN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.CONSENT_WITHDRAWN.code());
    }

    @Test
    @DisplayName("旧链接语义：学生不存在 → UNAUTHORIZED")
    void legacyLinkMissingStudentRejected() {
        stubParent("parent", TokenType.PARENT_REPORT, studentUserId);
        when(parentService.getStudent(tenantId, studentUserId)).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveLegacyLink("Bearer " + TOKEN))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("requireLinkedStudent：未绑定 → UNAUTHORIZED")
    void unlinkedRejected() {
        var identity = new ParentIdentityResolver.ParentIdentity(parentId, tenantId);
        when(parentService.isLinked(parentId, studentUserId)).thenReturn(false);

        assertThatThrownBy(() -> resolver.requireLinkedStudent(identity, studentUserId))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("requireLinkedStudent：已绑定 + withdrawn → CONSENT_WITHDRAWN（统一拦截）")
    void linkedWithdrawnRejected() {
        var identity = new ParentIdentityResolver.ParentIdentity(parentId, tenantId);
        when(parentService.isLinked(parentId, studentUserId)).thenReturn(true);
        User student = new User();
        student.setStatus(ConsentWithdrawalService.STATUS_WITHDRAWN);
        when(parentService.getStudent(tenantId, studentUserId)).thenReturn(student);

        assertThatThrownBy(() -> resolver.requireLinkedStudent(identity, studentUserId))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.CONSENT_WITHDRAWN.code());
    }

    @Test
    @DisplayName("requireLinkedOnly：已绑定 + withdrawn → 放行（状态端点宽松，BUG-P-P04-01）")
    void requireLinkedOnlyAllowsWithdrawn() {
        var identity = new ParentIdentityResolver.ParentIdentity(parentId, tenantId);
        when(parentService.isLinked(parentId, studentUserId)).thenReturn(true);

        resolver.requireLinkedOnly(identity, studentUserId); // 不抛异常即通过
    }
}
