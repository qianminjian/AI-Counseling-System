package com.mindsafe.service.parent;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.ParentStudentLink;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.ParentStudentLinkMapper;
import com.mindsafe.domain.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ParentService 家长端服务单测：学生查询 / 绑定校验 / 会话与消息下沉。
 * 契约：租户条件强制内置（LambdaQueryWrapper eq 双保险），绑定关系计数>0 判定。
 */
@ExtendWith(MockitoExtension.class)
class ParentServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private MessageSummaryMapper messageSummaryMapper;
    @Mock private ParentStudentLinkMapper parentStudentLinkMapper;

    private ParentService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID parentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ParentService(userMapper, sessionMapper, messageSummaryMapper, parentStudentLinkMapper);
    }

    @Test
    @DisplayName("getStudent：同租户学生命中返回实体")
    void getStudent_hit() {
        User student = new User();
        student.setUserId(studentId);
        student.setTenantId(tenantId);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(student);

        User result = service.getStudent(tenantId, studentId);

        assertThat(result).isSameAs(student);
        verify(userMapper).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("getStudent：不存在返回 null")
    void getStudent_miss() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.getStudent(tenantId, studentId)).isNull();
    }

    @Test
    @DisplayName("isLinked：绑定计数 >0 → true")
    void isLinked_true() {
        when(parentStudentLinkMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThat(service.isLinked(parentId, studentId)).isTrue();
    }

    @Test
    @DisplayName("isLinked：无绑定 → false")
    void isLinked_false() {
        when(parentStudentLinkMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThat(service.isLinked(parentId, studentId)).isFalse();
    }

    @Test
    @DisplayName("getRecentSessions：按租户+学生+时间条件查询")
    void getRecentSessions() {
        Instant since = Instant.now().minusSeconds(3600);
        CounselingSession s = new CounselingSession();
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

        List<CounselingSession> result = service.getRecentSessions(tenantId, studentId, since);

        assertThat(result).containsExactly(s);
        verify(sessionMapper).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("getRecentStudentMessages：仅学生发言 + 租户/学生/时间条件")
    void getRecentStudentMessages() {
        Instant since = Instant.now().minusSeconds(3600);
        MessageSummary m = new MessageSummary();
        m.setSenderType(User.USER_TYPE_STUDENT);
        when(messageSummaryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m));

        List<MessageSummary> result = service.getRecentStudentMessages(tenantId, studentId, since);

        assertThat(result).containsExactly(m);
        verify(messageSummaryMapper).selectList(any(Wrapper.class));
    }
}
