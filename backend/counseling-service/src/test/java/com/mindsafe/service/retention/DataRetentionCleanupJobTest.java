package com.mindsafe.service.retention;

import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.EmotionDiary;
import com.mindsafe.domain.entity.LongTermMemory;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.entity.VoiceprintEmbedding;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.domain.mapper.LongTermMemoryMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * DataRetentionCleanupJob 单元测试（doing/92 R-009②：撤回学生优先清理）。
 * <p>
 * 覆盖：withdrawn 账号的声纹/长期记忆/情绪日记撤回即删 + 审计登记；无撤回学生时不删。
 * （保留期清理段由既有行为保证，本测试聚焦新增撤回优先段）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据保留期清理（R-009② 撤回学生优先）")
class DataRetentionCleanupJobTest {

    @Mock private MessageSummaryMapper messageSummaryMapper;
    @Mock private CounselingSessionMapper sessionMapper;
    @Mock private UserMapper userMapper;
    @Mock private VoiceprintEmbeddingMapper voiceprintEmbeddingMapper;
    @Mock private LongTermMemoryMapper longTermMemoryMapper;
    @Mock private EmotionDiaryMapper emotionDiaryMapper;
    @Mock private AuditLogService auditLogService;

    private DataRetentionCleanupJob job;

    @BeforeEach
    void setUp() {
        // 纯单测环境无 MyBatis 启动：手动初始化实体元数据缓存（供 LambdaQueryWrapper 解析）
        initMybatisMeta(User.class);
        initMybatisMeta(VoiceprintEmbedding.class);
        initMybatisMeta(LongTermMemory.class);
        initMybatisMeta(EmotionDiary.class);
        initMybatisMeta(MessageSummary.class);
        initMybatisMeta(CounselingSession.class);

        job = new DataRetentionCleanupJob(
                messageSummaryMapper, sessionMapper, userMapper,
                voiceprintEmbeddingMapper, longTermMemoryMapper, emotionDiaryMapper,
                auditLogService, 180, 365);
    }

    /** 纯单测环境无 MyBatis 启动：手动初始化实体元数据缓存（BadgeServiceTest/SessionControllerTest 同款先例） */
    private static void initMybatisMeta(Class<?> entityClass) {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        // 纯单测环境无 JDBC 类型映射：UUID 显式注册（SessionControllerTest 同款先例）
        configuration.getTypeHandlerRegistry().register(UUID.class, org.apache.ibatis.type.ObjectTypeHandler.class);
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, entityClass);
    }

    @Test
    @DisplayName("有撤回学生 → 声纹/长期记忆/情绪日记三表即删 + 专项审计")
    void withdrawnStudents_dataDeletedAndAudited() {
        UUID withdrawnId = UUID.randomUUID();
        User withdrawn = new User();
        withdrawn.setUserId(withdrawnId);
        withdrawn.setStatus(User.STATUS_WITHDRAWN);
        when(userMapper.selectList(any())).thenReturn(List.of(withdrawn));
        when(voiceprintEmbeddingMapper.delete(any())).thenReturn(2);
        when(longTermMemoryMapper.delete(any())).thenReturn(1);
        when(emotionDiaryMapper.delete(any())).thenReturn(3);

        job.executeCleanup();

        // 安全关键：删除范围必须限定在 withdrawn 学生 id 集合（IN 条件），不得全表删
        assertDeleteIn(voiceprintEmbeddingMapper, withdrawnId);
        assertDeleteIn(longTermMemoryMapper, withdrawnId);
        assertDeleteIn(emotionDiaryMapper, withdrawnId);
        // 专项审计（PIPL §47 删除权留痕；受审计基建限制可能无法落库，见 Job 注释登记）
        verify(auditLogService).log(eq(null), eq(null), eq("DATA_RETENTION_WITHDRAWAL_CLEANUP"),
                eq("system"), eq(null), anyString());
    }

    /** 断言某 mapper 的 delete wrapper IN 条件值包含指定学生 id（防误删全表） */
    private static <T> void assertDeleteIn(com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper, UUID studentId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<T>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).delete(captor.capture());
        // MP 3.5.9 惰性参数：getCustomSqlSegment 触发 MPGENVAL 填充后再快照（值类型 String/UUID，统一 toString）
        captor.getValue().getCustomSqlSegment();
        Object[] vals = captor.getValue().getParamNameValuePairs().values().toArray();
        Assertions.assertThat(vals)
                .anyMatch(v -> v != null && v.toString().contains(studentId.toString()));
    }

    @Test
    @DisplayName("无撤回学生 → 三表不删（幂等空跑）")
    void noWithdrawnStudents_nothingDeleted() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        job.executeCleanup();

        verify(voiceprintEmbeddingMapper, never()).delete(any());
        verify(longTermMemoryMapper, never()).delete(any());
        verify(emotionDiaryMapper, never()).delete(any());
    }

    @Test
    @DisplayName("撤回查询钉住 status=withdrawn + user_type=student 双条件")
    void withdrawnQuery_filtersByStatusAndUserType() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        job.executeCleanup();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<User>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectList(captor.capture());
        LambdaQueryWrapper<User> wrapper = captor.getValue();
        Assertions.assertThat(wrapper.getCustomSqlSegment())
                .contains("status")
                .contains("user_type");
        Assertions.assertThat(wrapper.getParamNameValuePairs().values())
                .contains(User.STATUS_WITHDRAWN, User.USER_TYPE_STUDENT);
    }
}
