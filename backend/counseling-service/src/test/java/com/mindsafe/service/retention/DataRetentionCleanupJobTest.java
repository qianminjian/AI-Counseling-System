package com.mindsafe.service.retention;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.EmotionDiaryMapper;
import com.mindsafe.domain.mapper.LongTermMemoryMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.domain.mapper.VoiceprintEmbeddingMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataRetentionCleanupJob 测试（专题 D P0-1 联动：系统级审计落库断言）
 * <p>
 * 修复前 audit_logs.tenant_id NOT NULL（V7:32），本任务 3 处以 tenantId=null 调用
 * auditLogService.log 全部无法落库——数据删除这一最敏感操作无审计可查。
 * V47 迁移放开 NOT NULL 后，系统级审计（tenantId=null）可正常落库；本测试断言
 * 三条审计路径（主任务/异常分支/撤回学生清理）均以系统级参数调用审计服务。
 */
@DisplayName("DataRetentionCleanupJob 系统级审计落库")
class DataRetentionCleanupJobTest {

    /** 纯单元测试无 Spring/MyBatis 上下文：预注册实体 TableInfo（同 TeacherClassScopeTest 先例），
     * 否则 doCleanup 内构建 LambdaQueryWrapper 抛「can not find lambda cache」 */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class, org.apache.ibatis.type.ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), MessageSummary.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
    }

    private MessageSummaryMapper messageSummaryMapper;
    private CounselingSessionMapper sessionMapper;
    private UserMapper userMapper;
    private VoiceprintEmbeddingMapper voiceprintEmbeddingMapper;
    private LongTermMemoryMapper longTermMemoryMapper;
    private EmotionDiaryMapper emotionDiaryMapper;
    private AuditLogService auditLogService;
    private DataRetentionCleanupJob job;

    @BeforeEach
    void setUp() {
        messageSummaryMapper = mock(MessageSummaryMapper.class);
        sessionMapper = mock(CounselingSessionMapper.class);
        userMapper = mock(UserMapper.class);
        voiceprintEmbeddingMapper = mock(VoiceprintEmbeddingMapper.class);
        longTermMemoryMapper = mock(LongTermMemoryMapper.class);
        emotionDiaryMapper = mock(EmotionDiaryMapper.class);
        auditLogService = mock(AuditLogService.class);
        job = new DataRetentionCleanupJob(messageSummaryMapper, sessionMapper, userMapper,
                voiceprintEmbeddingMapper, longTermMemoryMapper, emotionDiaryMapper,
                auditLogService, 180, 365);
    }

    @Test
    @DisplayName("D-P0-1: 主任务完成后以系统级审计（tenantId=null）落库 DATA_RETENTION_CLEANUP")
    void cleanup_success_logsSystemAudit() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        job.executeCleanup();

        verify(auditLogService).log(isNull(), isNull(), eq("DATA_RETENTION_CLEANUP"), eq("system"), isNull(), anyString());
    }

    @Test
    @DisplayName("D-P0-1: 任务异常分支以系统级审计落库 DATA_RETENTION_CLEANUP_ERROR")
    void cleanup_failure_logsErrorAudit() {
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(messageSummaryMapper.delete(any())).thenThrow(new RuntimeException("db down"));

        job.executeCleanup();

        verify(auditLogService).log(isNull(), isNull(), eq("DATA_RETENTION_CLEANUP_ERROR"),
                eq("system"), isNull(), anyString());
    }

    @Test
    @DisplayName("D-P0-1: 撤回学生优先清理以系统级审计落库 DATA_RETENTION_WITHDRAWAL_CLEANUP")
    void cleanupWithdrawn_logsSystemAudit() {
        User withdrawn = new User();
        withdrawn.setUserId(UUID.randomUUID());
        withdrawn.setStatus(User.STATUS_WITHDRAWN);
        withdrawn.setUserType(User.USER_TYPE_STUDENT);
        when(userMapper.selectList(any())).thenReturn(List.of(withdrawn));

        job.executeCleanup();

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(isNull(), isNull(), eq("DATA_RETENTION_WITHDRAWAL_CLEANUP"),
                eq("system"), isNull(), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).contains("1 人");
        // 删除动作确实下发到对应 mapper（撤回即删口径）
        verify(voiceprintEmbeddingMapper).delete(any());
        verify(longTermMemoryMapper).delete(any());
        verify(emotionDiaryMapper).delete(any());
    }

    @Test
    @DisplayName("无撤回学生时跳过清理且不发撤回审计")
    void cleanup_noWithdrawnSkips() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        job.executeCleanup();

        verify(auditLogService).log(isNull(), isNull(), eq("DATA_RETENTION_CLEANUP"),
                eq("system"), isNull(), anyString());
        verify(auditLogService, never()).log(isNull(), isNull(), eq("DATA_RETENTION_WITHDRAWAL_CLEANUP"),
                eq("system"), isNull(), anyString());
    }
}
