package com.mindsafe.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 加密字段注册表（99-7，2026-08-14）——"哪些字段被加密"的单一事实源。
 * <p>
 * 此前字段清单散落三处（EncryptionBackfillService 四方法样板 / FieldEncryptionService
 * javadoc / V32·V49 迁移注释），新增字段需同步多处且抽象面≈实现面。本注册表声明式
 * 描述每个加密字段（表/列/实体访问器），回填循环、迁移容量注释、审计盘点统一派生。
 * 类型安全：使用方法引用而非反射（B-05 回填组件改造承接）。
 * <p>
 * 当前加密字段（B-05 盘点，与 V32/V49 容量迁移对齐）：
 * <ul>
 *   <li>message_summaries.content_summary（V32 扩 TEXT）</li>
 *   <li>counseling_sessions.session_summary（V9 TEXT）</li>
 *   <li>teacher_notes.content（V49 扩 TEXT）</li>
 *   <li>toc_family_accounts.phone（V49 扩 VARCHAR(96)，平台级表）</li>
 * </ul>
 */
@Component
public class EncryptedFieldRegistry {

    /** 回填分批大小（回填循环与分页查询一致） */
    public static final int BATCH_SIZE = 500;

    /** 加密字段描述（泛型 T = 实体类型；访问器均为方法引用，保类型安全） */
    public static final class BackfillableField<T> {
        private final String tableName;
        private final String columnName;
        private final Function<UUID, List<T>> pageFetcher;
        private final Function<T, UUID> idGetter;
        private final Function<T, String> valueGetter;
        private final BiConsumer<UUID, String> valueUpdater;

        BackfillableField(String tableName, String columnName,
                          Function<UUID, List<T>> pageFetcher,
                          Function<T, UUID> idGetter,
                          Function<T, String> valueGetter,
                          BiConsumer<UUID, String> valueUpdater) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.pageFetcher = pageFetcher;
            this.idGetter = idGetter;
            this.valueGetter = valueGetter;
            this.valueUpdater = valueUpdater;
        }

        public String tableName() { return tableName; }
        public String columnName() { return columnName; }
        public Function<UUID, List<T>> pageFetcher() { return pageFetcher; }
        public Function<T, UUID> idGetter() { return idGetter; }
        public Function<T, String> valueGetter() { return valueGetter; }
        public BiConsumer<UUID, String> valueUpdater() { return valueUpdater; }
    }

    private final List<BackfillableField<?>> fields;

    public EncryptedFieldRegistry(MessageSummaryMapper messageSummaryMapper,
                                  CounselingSessionMapper counselingSessionMapper,
                                  TeacherNoteMapper teacherNoteMapper,
                                  TocFamilyAccountMapper tocFamilyAccountMapper) {
        this.fields = List.of(
                new BackfillableField<>("message_summaries", "content_summary",
                        cursor -> messageSummaryMapper.selectList(new LambdaQueryWrapper<MessageSummary>()
                                .select(MessageSummary::getSummaryId, MessageSummary::getContentSummary)
                                .gt(cursor != null, MessageSummary::getSummaryId, cursor)
                                .orderByAsc(MessageSummary::getSummaryId)
                                .last("LIMIT " + BATCH_SIZE)),
                        MessageSummary::getSummaryId, MessageSummary::getContentSummary,
                        (id, encrypted) -> {
                            MessageSummary upd = new MessageSummary();
                            upd.setSummaryId(id);
                            upd.setContentSummary(encrypted);
                            messageSummaryMapper.updateById(upd);
                        }),
                new BackfillableField<>("counseling_sessions", "session_summary",
                        cursor -> counselingSessionMapper.selectList(new LambdaQueryWrapper<CounselingSession>()
                                .select(CounselingSession::getSessionId, CounselingSession::getSessionSummary)
                                .gt(cursor != null, CounselingSession::getSessionId, cursor)
                                .orderByAsc(CounselingSession::getSessionId)
                                .last("LIMIT " + BATCH_SIZE)),
                        CounselingSession::getSessionId, CounselingSession::getSessionSummary,
                        (id, encrypted) -> {
                            CounselingSession upd = new CounselingSession();
                            upd.setSessionId(id);
                            upd.setSessionSummary(encrypted);
                            counselingSessionMapper.updateById(upd);
                        }),
                new BackfillableField<>("teacher_notes", "content",
                        cursor -> teacherNoteMapper.selectList(new LambdaQueryWrapper<TeacherNote>()
                                .select(TeacherNote::getNoteId, TeacherNote::getContent)
                                .gt(cursor != null, TeacherNote::getNoteId, cursor)
                                .orderByAsc(TeacherNote::getNoteId)
                                .last("LIMIT " + BATCH_SIZE)),
                        TeacherNote::getNoteId, TeacherNote::getContent,
                        (id, encrypted) -> {
                            TeacherNote upd = new TeacherNote();
                            upd.setNoteId(id);
                            upd.setContent(encrypted);
                            teacherNoteMapper.updateById(upd);
                        }),
                new BackfillableField<>("toc_family_accounts", "phone",
                        cursor -> tocFamilyAccountMapper.selectList(new LambdaQueryWrapper<TocFamilyAccount>()
                                .select(TocFamilyAccount::getFamilyAccountId, TocFamilyAccount::getPhone)
                                .gt(cursor != null, TocFamilyAccount::getFamilyAccountId, cursor)
                                .orderByAsc(TocFamilyAccount::getFamilyAccountId)
                                .last("LIMIT " + BATCH_SIZE)),
                        TocFamilyAccount::getFamilyAccountId, TocFamilyAccount::getPhone,
                        (id, encrypted) -> {
                            TocFamilyAccount upd = new TocFamilyAccount();
                            upd.setFamilyAccountId(id);
                            upd.setPhone(encrypted);
                            tocFamilyAccountMapper.updateById(upd);
                        }));
    }

    /** 注册表全部加密字段（单一事实源；审计/文档派生入口） */
    public List<BackfillableField<?>> fields() {
        return fields;
    }
}
