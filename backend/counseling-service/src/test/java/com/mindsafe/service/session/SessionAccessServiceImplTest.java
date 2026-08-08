package com.mindsafe.service.session;

import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionAccessServiceImpl 单测（B5：班级范围查询收编后的单测收敛点）。
 * <p>
 * 契约：
 * - listClassStudents：租户条件强制内置（调用方无法漏写）、仅查本班学生；
 * - classScope 为 null/空白 → 返回空列表且不触库（空班/未绑定班级由调用方提前短路）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话访问与班级范围查询")
class SessionAccessServiceImplTest {

    @Mock
    private CounselingSessionMapper sessionMapper;
    @Mock
    private UserMapper userMapper;

    private SessionAccessServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SessionAccessServiceImpl(sessionMapper, userMapper);
    }

    private User student(UUID id, String classCode) {
        User u = new User();
        u.setUserId(id);
        u.setTenantId(tenantId);
        u.setUserType("student");
        u.setClassCode(classCode);
        return u;
    }

    @Test
    @DisplayName("班级学生列表：透传租户内该班学生（user_type=student + class_code 条件内置）")
    void listClassStudents_returnsClassStudents() {
        UUID s1 = UUID.randomUUID();
        when(userMapper.selectList(any())).thenReturn(List.of(student(s1, "C1")));

        List<User> students = service.listClassStudents(tenantId, "C1");

        assertThat(students).hasSize(1);
        assertThat(students.get(0).getUserId()).isEqualTo(s1);
        verify(userMapper).selectList(any());
    }

    @Test
    @DisplayName("班级学生列表：classScope 为 null → 空列表且不触库")
    void listClassStudents_nullScope_returnsEmptyWithoutQuery() {
        List<User> students = service.listClassStudents(tenantId, null);

        assertThat(students).isEmpty();
        verify(userMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("班级学生列表：classScope 为空白 → 空列表且不触库（未绑定班级语义）")
    void listClassStudents_blankScope_returnsEmptyWithoutQuery() {
        List<User> students = service.listClassStudents(tenantId, "  ");

        assertThat(students).isEmpty();
        verify(userMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("会话归属校验：跨租户会话不可见（原有契约回归）")
    void sessionBelongsToTenant_foreignSession_invisible() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        assertThat(service.sessionBelongsToTenant(tenantId, UUID.randomUUID())).isFalse();
        verify(sessionMapper).selectOne(any());
    }

    @Test
    @DisplayName("会话归属校验：本租户会话可见")
    void sessionBelongsToTenant_ownSession_visible() {
        when(sessionMapper.selectOne(any())).thenReturn(mock(com.mindsafe.domain.entity.CounselingSession.class));

        assertThat(service.sessionBelongsToTenant(tenantId, UUID.randomUUID())).isTrue();
    }
}
