package com.mindsafe.service.conversation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.typehandler.JsonbTypeHandler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * CounselingSessionStore 单元测试（BA-11：DB 会话读写仓储获得独立测试面——
 * 此前会话 Mapper 直连编排器，仓储逻辑零测试）。
 * <p>
 * 覆盖：透传读写、escalated 判定与异常降级、历史分页条件组装（租户+学生+倒序+limit 上限）、
 * 归属校验条件组装（租户+学生+会话三重条件）。
 */
class CounselingSessionStoreTest {

    private CounselingSessionMapper sessionMapper;
    private CounselingSessionStore store;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    /** 纯单元测试无 Spring 上下文：预注册 TableInfo（UUID/Jsonb 字段需要显式 type handler） */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, ObjectTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(JsonbTypeHandler.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(CounselingSessionMapper.class);
        store = new CounselingSessionStore(sessionMapper);
    }

    @Nested
    @DisplayName("透传读写")
    class Passthrough {

        @Test
        @DisplayName("insert 透传实体落库")
        void insertDelegates() {
            CounselingSession entity = new CounselingSession();
            entity.setSessionId(sessionId);
            store.insert(entity);
            verify(sessionMapper).insert(entity);
        }

        @Test
        @DisplayName("updateById 透传部分更新")
        void updateDelegates() {
            CounselingSession update = new CounselingSession();
            update.setSessionId(sessionId);
            update.setTurnCount(5);
            store.updateById(update);
            verify(sessionMapper).updateById(update);
        }

        @Test
        @DisplayName("findById 透传并按主键返回")
        void findByIdDelegates() {
            CounselingSession entity = new CounselingSession();
            entity.setSessionId(sessionId);
            when(sessionMapper.selectById(sessionId)).thenReturn(entity);
            assertThat(store.findById(sessionId)).isSameAs(entity);
        }
    }

    @Nested
    @DisplayName("escalated 判定")
    class Escalated {

        @Test
        @DisplayName("会话状态为 escalated 返回 true")
        void escalatedStatusReturnsTrue() {
            CounselingSession entity = new CounselingSession();
            entity.setSessionStatus("escalated");
            when(sessionMapper.selectById(sessionId)).thenReturn(entity);
            assertThat(store.isEscalated(sessionId)).isTrue();
        }

        @Test
        @DisplayName("非 escalated / 不存在会话返回 false")
        void normalOrMissingReturnsFalse() {
            CounselingSession normal = new CounselingSession();
            normal.setSessionStatus("active");
            when(sessionMapper.selectById(sessionId)).thenReturn(normal);
            assertThat(store.isEscalated(sessionId)).isFalse();

            when(sessionMapper.selectById(sessionId)).thenReturn(null);
            assertThat(store.isEscalated(sessionId)).isFalse();
        }

        @Test
        @DisplayName("查询异常降级 false（决策模型层有风险信号兜底，不阻断暖场）")
        void queryFailureDegradesToFalse() {
            when(sessionMapper.selectById(sessionId)).thenThrow(new RuntimeException("db down"));
            assertThat(store.isEscalated(sessionId)).isFalse();
        }
    }

    @Nested
    @DisplayName("会话历史查询")
    class History {

        @Test
        @DisplayName("组装租户+学生双重条件与 startedAt 倒序，limit 上限 50 内置")
        void assemblesTenantStudentAndLimitCap() {
            CounselingSession s1 = new CounselingSession();
            s1.setSessionId(sessionId);
            Page<CounselingSession> page = new Page<>(1, 50, false);
            page.setRecords(List.of(s1));
            when(sessionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

            List<CounselingSession> result = store.findHistory(tenantId, studentId, 100);

            assertThat(result).containsExactly(s1);
            ArgumentCaptor<Page<CounselingSession>> pageCaptor = ArgumentCaptor.forClass(Page.class);
            ArgumentCaptor<LambdaQueryWrapper<CounselingSession>> wrapperCaptor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(sessionMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
            // limit 上限 50 内置（防大分页拖库）
            assertThat(pageCaptor.getValue().getSize()).isEqualTo(50);
            assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
            // 租户+学生双重条件 + startedAt 倒序（SEC-001 防跨学生/跨租户泄漏）
            LambdaQueryWrapper<CounselingSession> wrapper = wrapperCaptor.getValue();
            assertThat(wrapper.getSqlSegment())
                    .contains("tenant_id")
                    .contains("student_user_id")
                    .contains("ORDER BY")
                    .contains("started_at");
            assertThat(wrapper.getParamNameValuePairs().values())
                    .contains(tenantId, studentId);
        }

        @Test
        @DisplayName("limit 小于 50 时按请求值透传")
        void limitUnderCapPassedThrough() {
            when(sessionMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(new Page<>());
            store.findHistory(tenantId, studentId, 20);
            ArgumentCaptor<Page<CounselingSession>> pageCaptor = ArgumentCaptor.forClass(Page.class);
            verify(sessionMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
            assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("会话归属校验")
    class Ownership {

        @Test
        @DisplayName("组装租户+学生+会话三重条件")
        void assemblesTripleCondition() {
            CounselingSession existing = new CounselingSession();
            existing.setSessionId(sessionId);
            when(sessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

            assertThat(store.findOwned(tenantId, studentId, sessionId)).isSameAs(existing);
            ArgumentCaptor<LambdaQueryWrapper<CounselingSession>> wrapperCaptor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(sessionMapper).selectOne(wrapperCaptor.capture());
            // 租户+学生+会话三重条件（非持有人拒绝评价，防他人评价劫持）
            LambdaQueryWrapper<CounselingSession> wrapper = wrapperCaptor.getValue();
            assertThat(wrapper.getSqlSegment())
                    .contains("tenant_id")
                    .contains("student_user_id")
                    .contains("session_id");
            assertThat(wrapper.getParamNameValuePairs().values())
                    .contains(tenantId, studentId, sessionId);
        }

        @Test
        @DisplayName("无匹配记录返回 null（非持有人）")
        void noMatchReturnsNull() {
            when(sessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            assertThat(store.findOwned(tenantId, studentId, sessionId)).isNull();
        }
    }
}
