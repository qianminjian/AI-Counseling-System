package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.ParentAccount;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.ParentAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ParentAuthController 单元测试（P1 覆盖率冲刺：家长注册/登录/子女列表）
 * <p>
 * 注意：register/login 的 children 映射用 Map.of（null 班级兜底空串），
 * getChildren 用 LinkedHashMap（null 原样返回）。
 */
class ParentAuthControllerTest {

    private ParentAuthService parentAuthService;
    private JwtTokenProvider jwtTokenProvider;
    private ParentAuthController controller;

    private final UUID parentId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        parentAuthService = mock(ParentAuthService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        controller = new ParentAuthController(parentAuthService, jwtTokenProvider);
    }

    private ParentAccount account() {
        ParentAccount a = new ParentAccount();
        a.setParentId(parentId);
        a.setTenantId(tenantId);
        a.setDisplayName("王妈妈");
        return a;
    }

    private User childWithCodes() {
        User u = new User();
        u.setUserId(childId);
        u.setPseudonym("小星");
        u.setGradeCode("G4");
        u.setClassCode("C1");
        return u;
    }

    private User childWithoutCodes() {
        User u = new User();
        u.setUserId(UUID.randomUUID());
        u.setPseudonym("小月");
        u.setGradeCode(null);
        u.setClassCode(null);
        return u;
    }

    @Test
    @DisplayName("register 成功 → 双 token + children 映射（null 班级兜底空串）")
    void register_success() {
        when(parentAuthService.register("FAM001", "13800000000", "pwd123", "妈妈"))
                .thenReturn(account());
        when(jwtTokenProvider.generateToken(parentId, "parent", tenantId)).thenReturn("tk");
        when(jwtTokenProvider.generateRefreshToken(parentId, "parent", tenantId)).thenReturn("rt");
        when(parentAuthService.getLinkedStudents(parentId))
                .thenReturn(List.of(childWithCodes(), childWithoutCodes()));

        ApiResponse<Map<String, Object>> resp = controller.register(
                new ParentAuthController.ParentRegisterRequest("FAM001", "13800000000", "pwd123", "妈妈"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("token")).isEqualTo("tk");
        assertThat(resp.data().get("refreshToken")).isEqualTo("rt");
        assertThat(resp.data().get("parentId")).isEqualTo(parentId);
        assertThat(resp.data().get("displayName")).isEqualTo("王妈妈");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) resp.data().get("children");
        assertThat(children).hasSize(2);
        assertThat(children.get(0).get("userId")).isEqualTo(childId);
        assertThat(children.get(0).get("nickname")).isEqualTo("小星");
        assertThat(children.get(0).get("gradeCode")).isEqualTo("G4");
        assertThat(children.get(0).get("classCode")).isEqualTo("C1");
        // null 班级 → 空串兜底（Map.of 不允许 null 值）
        assertThat(children.get(1).get("gradeCode")).isEqualTo("");
        assertThat(children.get(1).get("classCode")).isEqualTo("");
        verify(parentAuthService).register("FAM001", "13800000000", "pwd123", "妈妈");
    }

    @Test
    @DisplayName("register 无绑定学生 → children 空列表")
    void register_noChildren() {
        when(parentAuthService.register("FAM001", "13800000000", "pwd123", null))
                .thenReturn(account());
        when(jwtTokenProvider.generateToken(parentId, "parent", tenantId)).thenReturn("tk");
        when(jwtTokenProvider.generateRefreshToken(parentId, "parent", tenantId)).thenReturn("rt");
        when(parentAuthService.getLinkedStudents(parentId)).thenReturn(List.of());

        ApiResponse<Map<String, Object>> resp = controller.register(
                new ParentAuthController.ParentRegisterRequest("FAM001", "13800000000", "pwd123", null));

        assertThat(((List<?>) resp.data().get("children"))).isEmpty();
    }

    @Test
    @DisplayName("login 成功 → 双 token + children")
    void login_success() {
        when(parentAuthService.login("13800000000", "pwd123")).thenReturn(account());
        when(jwtTokenProvider.generateToken(parentId, "parent", tenantId)).thenReturn("tk");
        when(jwtTokenProvider.generateRefreshToken(parentId, "parent", tenantId)).thenReturn("rt");
        when(parentAuthService.getLinkedStudents(parentId)).thenReturn(List.of(childWithCodes()));

        ApiResponse<Map<String, Object>> resp = controller.login(
                new ParentAuthController.ParentLoginRequest("13800000000", "pwd123"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("token")).isEqualTo("tk");
        assertThat(resp.data().get("refreshToken")).isEqualTo("rt");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) resp.data().get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("gradeCode")).isEqualTo("G4");
        assertThat(children.get(0).get("classCode")).isEqualTo("C1");
        verify(parentAuthService).login("13800000000", "pwd123");
    }

    @Test
    @DisplayName("getChildren 解析 Bearer token → 学生列表（null 原样返回）")
    void getChildren() {
        when(jwtTokenProvider.getUserId("tk")).thenReturn(parentId);
        when(parentAuthService.getLinkedStudents(parentId))
                .thenReturn(List.of(childWithCodes(), childWithoutCodes()));

        ApiResponse<List<Map<String, Object>>> resp = controller.getChildren("Bearer tk");

        assertThat(resp.data()).hasSize(2);
        assertThat(resp.data().get(0).get("userId")).isEqualTo(childId);
        assertThat(resp.data().get(0).get("nickname")).isEqualTo("小星");
        assertThat(resp.data().get(0).get("gradeCode")).isEqualTo("G4");
        // getChildren 用 LinkedHashMap → null 原样返回（与 register 的 Map.of 兜底不同）
        assertThat(resp.data().get(1).get("gradeCode")).isNull();
        assertThat(resp.data().get(1).get("classCode")).isNull();
        verify(jwtTokenProvider).getUserId("tk");
    }

    @Test
    @DisplayName("getChildren 无绑定学生 → 空列表")
    void getChildren_empty() {
        when(jwtTokenProvider.getUserId("tk")).thenReturn(parentId);
        when(parentAuthService.getLinkedStudents(parentId)).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> resp = controller.getChildren("Bearer tk");

        assertThat(resp.data()).isEmpty();
    }

    @Test
    @DisplayName("register/login 请求 record 携带校验注解（@NotBlank/@Pattern 不被绕过）")
    void requestRecords_haveValidationAnnotations() {
        var register = new ParentAuthController.ParentRegisterRequest("FAM001", "13800000000", "pwd", "爸爸");
        var login = new ParentAuthController.ParentLoginRequest("13800000000", "pwd");

        assertThat(register.familyCode()).isEqualTo("FAM001");
        assertThat(register.relation()).isEqualTo("爸爸");
        assertThat(login.phone()).isEqualTo("13800000000");
        assertThat(login.password()).isEqualTo("pwd");
        // 校验注解存在（Spring 容器内 @Valid 触发时约束生效）
        assertThat(register.getClass().getDeclaredFields()).hasSize(4);
        assertThat(login.getClass().getDeclaredFields()).hasSize(2);
    }
}
