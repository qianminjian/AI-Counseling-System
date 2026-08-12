package com.mindsafe.api.controller;

import com.mindsafe.common.exception.BizException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.api.security.JwtTokenProvider;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.wecom.WeComOAuthService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeComOAuthController 单元测试（P1 覆盖率冲刺：企微免密登录 OAuth 链路）
 * <p>
 * @Value 字段与私有 restTemplate 通过反射注入（参照 VoiceprintControllerTest 模式）。
 */
class WeComOAuthControllerTest {

    private JwtTokenProvider jwtTokenProvider;
    private WeComOAuthService weComOAuthService;
    private RestTemplate restTemplate;
    private WeComOAuthController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), User.class);

        jwtTokenProvider = mock(JwtTokenProvider.class);
        weComOAuthService = mock(WeComOAuthService.class);
        restTemplate = mock(RestTemplate.class);
        controller = new WeComOAuthController(jwtTokenProvider, weComOAuthService);
        // 直接 new 不触发 @Value 注入 → 手动模拟默认空串（未配置分支依赖）
        ReflectionTestUtils.setField(controller, "corpId", "");
        ReflectionTestUtils.setField(controller, "agentId", "");
        ReflectionTestUtils.setField(controller, "secret", "");
        ReflectionTestUtils.setField(controller, "redirectUri", "");
    }

    /** 反射注入企微配置 + mock restTemplate（字段初始化器无法构造注入） */
    private void configureWeCom() {
        ReflectionTestUtils.setField(controller, "corpId", "ww123456");
        ReflectionTestUtils.setField(controller, "agentId", "1000002");
        ReflectionTestUtils.setField(controller, "secret", "secret-key");
        ReflectionTestUtils.setField(controller, "redirectUri", "https://app.mindsafe.cn/wecom/callback");
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
    }

    private User teacher() {
        User u = new User();
        u.setUserId(userId);
        u.setPseudonym("wx_zhang");
        u.setUserType("teacher");
        u.setTenantId(tenantId);
        return u;
    }

    @Test
    @DisplayName("getAuthUrl 未配置企微 → 501")
    void authUrl_notConfigured() {
        assertThatThrownBy(() -> controller.getAuthUrl())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("企业微信未配置");
    }

    @Test
    @DisplayName("getAuthUrl 已配置 → 返回带 appid/agentid 的授权 URL")
    void authUrl_configured() {
        configureWeCom();

        ApiResponse<Map<String, Object>> resp = controller.getAuthUrl();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("enabled")).isEqualTo(true);
        String url = (String) resp.data().get("authUrl");
        assertThat(url).contains("open.weixin.qq.com")
                .contains("appid=ww123456")
                .contains("agentid=1000002");
    }

    @Test
    @DisplayName("callback 缺 code → 400")
    void callback_missingCode() {
        assertThatThrownBy(() -> controller.callback(Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少授权码");
    }

    @Test
    @DisplayName("callback code 为空白 → 400")
    void callback_blankCode() {
        assertThatThrownBy(() -> controller.callback(Map.of("code", "  ")))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("callback 未配置企微 → 501")
    void callback_notConfigured() {
        assertThatThrownBy(() -> controller.callback(Map.of("code", "authcode")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("企业微信未配置");
    }

    @Test
    @DisplayName("callback 成功 → 匹配教师 + 更新登录时间 + 签发双 token")
    void callback_success() {
        configureWeCom();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "at_123"), Map.of("userid", "wx_zhang"));
        when(weComOAuthService.findTeacherByWeComId("wx_zhang")).thenReturn(teacher());
        when(jwtTokenProvider.generateToken(userId, "teacher", tenantId)).thenReturn("tk");
        when(jwtTokenProvider.generateRefreshToken(userId, "teacher", tenantId)).thenReturn("rt");

        ApiResponse<Map<String, Object>> resp = controller.callback(Map.of("code", "authcode"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("matched")).isEqualTo(true);
        assertThat(resp.data().get("token")).isEqualTo("tk");
        assertThat(resp.data().get("refreshToken")).isEqualTo("rt");
        assertThat(resp.data().get("userId")).isEqualTo(userId.toString());
        assertThat(resp.data().get("userType")).isEqualTo("teacher");
        assertThat(resp.data().get("tenantId")).isEqualTo(tenantId.toString());
        verify(weComOAuthService).findTeacherByWeComId("wx_zhang");
        verify(weComOAuthService).touchLastLogin(tenantId, userId);
        verify(jwtTokenProvider).generateToken(userId, "teacher", tenantId);
        verify(jwtTokenProvider).generateRefreshToken(userId, "teacher", tenantId);
    }

    @Test
    @DisplayName("callback 企微未返回 userid → 401（不查询系统用户）")
    void callback_noUserId() {
        configureWeCom();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "at_123"), Map.of("errcode", 40014));

        assertThatThrownBy(() -> controller.callback(Map.of("code", "authcode")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("企微授权失败");
        verify(weComOAuthService, never()).findTeacherByWeComId(anyString());
    }

    @Test
    @DisplayName("callback 系统无匹配教师 → matched=false 提示后台绑定")
    void callback_noMatch() {
        configureWeCom();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenReturn(Map.of("access_token", "at_123"), Map.of("userid", "wx_unknown"));
        when(weComOAuthService.findTeacherByWeComId("wx_unknown")).thenReturn(null);

        ApiResponse<Map<String, Object>> resp = controller.callback(Map.of("code", "authcode"));

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.data().get("matched")).isEqualTo(false);
        assertThat(resp.data().get("wecomUserId")).isEqualTo("wx_unknown");
        assertThat((String) resp.data().get("message")).contains("后台绑定");
        verify(jwtTokenProvider, never()).generateToken(any(), any(), any());
    }

    @Test
    @DisplayName("callback 企微 API 异常 → 500 fail-closed")
    void callback_exception() {
        configureWeCom();
        when(restTemplate.getForObject(anyString(), eq(Map.class)))
                .thenThrow(new RuntimeException("连接超时"));

        assertThatThrownBy(() -> controller.callback(Map.of("code", "authcode")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("企微登录处理失败");
    }
}
