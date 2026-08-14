package com.mindsafe.service.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EncryptionBackfillService 单元测试（B-05，2026-08-14）
 */
class EncryptionBackfillServiceTest {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="; // 32 字节

    private MessageSummaryMapper messageSummaryMapper;
    private CounselingSessionMapper counselingSessionMapper;
    private TeacherNoteMapper teacherNoteMapper;
    private TocFamilyAccountMapper tocFamilyAccountMapper;
    private FieldEncryptionService encryptionService;
    private EncryptionBackfillService backfillService;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus lambda 缓存初始化（先例 DataRetentionCleanupJobTest）
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(java.util.UUID.class, org.apache.ibatis.type.ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), MessageSummary.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), CounselingSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), TeacherNote.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), TocFamilyAccount.class);

        messageSummaryMapper = mock(MessageSummaryMapper.class);
        counselingSessionMapper = mock(CounselingSessionMapper.class);
        teacherNoteMapper = mock(TeacherNoteMapper.class);
        tocFamilyAccountMapper = mock(TocFamilyAccountMapper.class);
        encryptionService = new FieldEncryptionService(true, TEST_KEY, 1, "", new StandardEnvironment());
        EncryptedFieldRegistry registry = new EncryptedFieldRegistry(messageSummaryMapper,
                counselingSessionMapper, teacherNoteMapper, tocFamilyAccountMapper);
        backfillService = new EncryptionBackfillService(encryptionService, registry);
    }

    private MessageSummary summary(UUID id, String content) {
        MessageSummary s = new MessageSummary();
        s.setSummaryId(id);
        s.setContentSummary(content);
        return s;
    }

    @Test
    @DisplayName("B-05：明文行回填为密文（v1: 前缀），幂等跳过已加密行")
    void backfillPlaintextAndSkipEncrypted() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        when(messageSummaryMapper.selectList(any())).thenReturn(
                List.of(summary(id1, "明文摘要一"), summary(id2, "v1:YWJjZA=="), summary(id3, null)));
        when(messageSummaryMapper.updateById(any(MessageSummary.class))).thenReturn(1);

        var report = backfillService.backfillAll();

        assertThat(report.counts().get("message_summaries")).isEqualTo(1L); // 仅 id1 回填；id2 已加密跳过；id3 null 跳过
        assertThat(report.total()).isEqualTo(1);
        verify(messageSummaryMapper).updateById(org.mockito.ArgumentMatchers.argThat((MessageSummary m) ->
                m.getSummaryId().equals(id1)
                        && m.getContentSummary().startsWith("v1:")
                        && m.getContentSummary().length() > 10));
    }

    @Test
    @DisplayName("B-05：四表全量回填（会话摘要/教师备注/家庭手机号）")
    void backfillAllTables() {
        UUID s1 = UUID.randomUUID();
        UUID n1 = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        when(messageSummaryMapper.selectList(any())).thenReturn(List.of());
        when(counselingSessionMapper.selectList(any())).thenReturn(List.of(session(s1, "会话摘要")));
        when(teacherNoteMapper.selectList(any())).thenReturn(List.of(note(n1, "教师备注")));
        when(tocFamilyAccountMapper.selectList(any())).thenReturn(List.of(account(f1, "13900000001")));
        when(counselingSessionMapper.updateById(any(CounselingSession.class))).thenReturn(1);
        when(teacherNoteMapper.updateById(any(TeacherNote.class))).thenReturn(1);
        when(tocFamilyAccountMapper.updateById(any(TocFamilyAccount.class))).thenReturn(1);

        var report = backfillService.backfillAll();

        assertThat(report.counts().get("counseling_sessions")).isEqualTo(1L);
        assertThat(report.counts().get("teacher_notes")).isEqualTo(1L);
        assertThat(report.counts().get("toc_family_accounts")).isEqualTo(1L);
        assertThat(report.total()).isEqualTo(3);
        verify(counselingSessionMapper).updateById(org.mockito.ArgumentMatchers.argThat((CounselingSession c) ->
                c.getSessionId().equals(s1)));
        verify(teacherNoteMapper).updateById(org.mockito.ArgumentMatchers.argThat((TeacherNote t) ->
                t.getNoteId().equals(n1)));
        verify(tocFamilyAccountMapper).updateById(org.mockito.ArgumentMatchers.argThat((TocFamilyAccount f) ->
                f.getFamilyAccountId().equals(f1)));
    }

    @Test
    @DisplayName("B-05：超过 500 行时按主键游标推进分批（第二页继续回填）")
    void backfillPaginates() {
        List<MessageSummary> page1 = new ArrayList<>();
        UUID lastId = null;
        for (int i = 0; i < 500; i++) {
            UUID id = UUID.randomUUID();
            page1.add(summary(id, "明文" + i));
            lastId = id;
        }
        UUID id501 = UUID.randomUUID();
        when(messageSummaryMapper.selectList(any())).thenReturn(page1, List.of(summary(id501, "明文501")));
        when(messageSummaryMapper.updateById(any(MessageSummary.class))).thenReturn(1);

        var report = backfillService.backfillAll();

        assertThat(report.counts().get("message_summaries")).isEqualTo(501L);
        verify(messageSummaryMapper, org.mockito.Mockito.times(2)).selectList(any());
        verify(messageSummaryMapper, org.mockito.Mockito.times(501)).updateById(any(MessageSummary.class));
    }

    @Test
    @DisplayName("B-05：单行回填失败不阻断（跳过继续），其余行正常处理")
    void backfillContinuesOnError() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(messageSummaryMapper.selectList(any())).thenReturn(
                List.of(summary(id1, "坏数据"), summary(id2, "好数据")));
        when(messageSummaryMapper.updateById(any(MessageSummary.class))).thenThrow(new RuntimeException("db down"))
                .thenReturn(1);

        var report = backfillService.backfillAll();

        assertThat(report.counts().get("message_summaries")).isEqualTo(1L); // id2 成功，id1 失败跳过
    }

    @Test
    @DisplayName("B-05：全量已加密时处理数为 0（幂等，重复执行安全）")
    void backfillIdempotent() {
        UUID id1 = UUID.randomUUID();
        when(messageSummaryMapper.selectList(any())).thenReturn(
                List.of(summary(id1, "v1:c2VjcmV0")));
        when(messageSummaryMapper.updateById(any(MessageSummary.class))).thenReturn(1);

        var report = backfillService.backfillAll();

        assertThat(report.counts().get("message_summaries")).isZero();
        verify(messageSummaryMapper, never()).updateById(any(MessageSummary.class));
    }

    private CounselingSession session(UUID id, String summary) {
        CounselingSession s = new CounselingSession();
        s.setSessionId(id);
        s.setSessionSummary(summary);
        return s;
    }

    private TeacherNote note(UUID id, String content) {
        TeacherNote n = new TeacherNote();
        n.setNoteId(id);
        n.setContent(content);
        return n;
    }

    private TocFamilyAccount account(UUID id, String phone) {
        TocFamilyAccount a = new TocFamilyAccount();
        a.setFamilyAccountId(id);
        a.setPhone(phone);
        return a;
    }
}
