package com.mindsafe.service.wecom;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeComOAuthService 企微 OAuth 用户服务单测：教师匹配（系统作用域）+ 最后登录时间更新。
 */
@ExtendWith(MockitoExtension.class)
class WeComOAuthServiceTest {

    @Mock private UserMapper userMapper;

    private WeComOAuthService service;

    @BeforeEach
    void setUp() {
        service = new WeComOAuthService(userMapper);
    }

    @Test
    @DisplayName("findTeacherByWeComId：命中教师返回实体")
    void findTeacherByWeComId_hit() {
        User teacher = new User();
        teacher.setPseudonym("wx_1001");
        teacher.setUserType(User.USER_TYPE_TEACHER);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(teacher);

        User result = service.findTeacherByWeComId("wx_1001");

        assertThat(result).isSameAs(teacher);
    }

    @Test
    @DisplayName("findTeacherByWeComId：未命中返回 null")
    void findTeacherByWeComId_miss() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.findTeacherByWeComId("nobody")).isNull();
    }

    @Test
    @DisplayName("touchLastLogin：更新登录时间并在结束清理租户上下文")
    void touchLastLogin() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.touchLastLogin(tenantId, userId);

        verify(userMapper).updateById(any(User.class));
        assertThat(TenantContextHolder.get()).isNull();
    }
}
