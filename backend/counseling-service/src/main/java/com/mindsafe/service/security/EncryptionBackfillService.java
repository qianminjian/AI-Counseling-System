package com.mindsafe.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.CounselingSession;
import com.mindsafe.domain.entity.MessageSummary;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.CounselingSessionMapper;
import com.mindsafe.domain.mapper.MessageSummaryMapper;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 存量明文加密回填（B-05，2026-08-14；COMP-008 启用配套，frozen/60）
 * <p>
 * ENCRYPTION_ENABLED=true 启动时由 {@link EncryptionBackfillRunner} 触发：对 4 个
 * 加密字段的存量明文行做一次性回填。幂等（isEncrypted 跳过，支持重复执行）：
 * <ul>
 *   <li>message_summaries.content_summary</li>
 *   <li>counseling_sessions.session_summary</li>
 *   <li>teacher_notes.content</li>
 *   <li>toc_family_accounts.phone（平台级表，忽略名单）</li>
 * </ul>
 * 分批游标（主键升序 + LIMIT 500），单行失败记日志不阻断（防止单条坏数据
 * 阻断全量回填）；全表扫描后输出汇总报告。租户行级表经 runAsSystem 系统作用域
 * 跨租户执行（先例 DataRetentionCleanupJob，M1-003 fail-fast 豁免）。
 */
@Service
public class EncryptionBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfillService.class);
    private static final int BATCH_SIZE = 500;

    private final FieldEncryptionService encryptionService;
    private final MessageSummaryMapper messageSummaryMapper;
    private final CounselingSessionMapper counselingSessionMapper;
    private final TeacherNoteMapper teacherNoteMapper;
    private final TocFamilyAccountMapper tocFamilyAccountMapper;

    public EncryptionBackfillService(FieldEncryptionService encryptionService,
                                     MessageSummaryMapper messageSummaryMapper,
                                     CounselingSessionMapper counselingSessionMapper,
                                     TeacherNoteMapper teacherNoteMapper,
                                     TocFamilyAccountMapper tocFamilyAccountMapper) {
        this.encryptionService = encryptionService;
        this.messageSummaryMapper = messageSummaryMapper;
        this.counselingSessionMapper = counselingSessionMapper;
        this.teacherNoteMapper = teacherNoteMapper;
        this.tocFamilyAccountMapper = tocFamilyAccountMapper;
    }

    /** 回填报告（各字段处理行数） */
    public record BackfillReport(long messageSummaries, long sessionSummaries, long teacherNotes, long familyPhones) {
        public long total() {
            return messageSummaries + sessionSummaries + teacherNotes + familyPhones;
        }
    }

    /** 回填全部加密字段（幂等；仅记录处理数，不抛错） */
    public BackfillReport backfillAll() {
        BackfillReport[] holder = new BackfillReport[1];
        TenantContextHolder.runAsSystem(() -> holder[0] = new BackfillReport(
                backfillMessageSummaries(),
                backfillSessionSummaries(),
                backfillTeacherNotes(),
                backfillFamilyPhones()));
        return holder[0];
    }

    private long backfillMessageSummaries() {
        return backfillPage(
                cursor -> messageSummaryMapper.selectList(new LambdaQueryWrapper<MessageSummary>()
                        .select(MessageSummary::getSummaryId, MessageSummary::getContentSummary)
                        .gt(cursor != null, MessageSummary::getSummaryId, cursor)
                        .orderByAsc(MessageSummary::getSummaryId)
                        .last("LIMIT " + BATCH_SIZE)),
                MessageSummary::getSummaryId,
                MessageSummary::getContentSummary,
                (id, encrypted) -> {
                    MessageSummary upd = new MessageSummary();
                    upd.setSummaryId(id);
                    upd.setContentSummary(encrypted);
                    messageSummaryMapper.updateById(upd);
                });
    }

    private long backfillSessionSummaries() {
        return backfillPage(
                cursor -> counselingSessionMapper.selectList(new LambdaQueryWrapper<CounselingSession>()
                        .select(CounselingSession::getSessionId, CounselingSession::getSessionSummary)
                        .gt(cursor != null, CounselingSession::getSessionId, cursor)
                        .orderByAsc(CounselingSession::getSessionId)
                        .last("LIMIT " + BATCH_SIZE)),
                CounselingSession::getSessionId,
                CounselingSession::getSessionSummary,
                (id, encrypted) -> {
                    CounselingSession upd = new CounselingSession();
                    upd.setSessionId(id);
                    upd.setSessionSummary(encrypted);
                    counselingSessionMapper.updateById(upd);
                });
    }

    private long backfillTeacherNotes() {
        return backfillPage(
                cursor -> teacherNoteMapper.selectList(new LambdaQueryWrapper<TeacherNote>()
                        .select(TeacherNote::getNoteId, TeacherNote::getContent)
                        .gt(cursor != null, TeacherNote::getNoteId, cursor)
                        .orderByAsc(TeacherNote::getNoteId)
                        .last("LIMIT " + BATCH_SIZE)),
                TeacherNote::getNoteId,
                TeacherNote::getContent,
                (id, encrypted) -> {
                    TeacherNote upd = new TeacherNote();
                    upd.setNoteId(id);
                    upd.setContent(encrypted);
                    teacherNoteMapper.updateById(upd);
                });
    }

    private long backfillFamilyPhones() {
        return backfillPage(
                cursor -> tocFamilyAccountMapper.selectList(new LambdaQueryWrapper<TocFamilyAccount>()
                        .select(TocFamilyAccount::getFamilyAccountId, TocFamilyAccount::getPhone)
                        .gt(cursor != null, TocFamilyAccount::getFamilyAccountId, cursor)
                        .orderByAsc(TocFamilyAccount::getFamilyAccountId)
                        .last("LIMIT " + BATCH_SIZE)),
                TocFamilyAccount::getFamilyAccountId,
                TocFamilyAccount::getPhone,
                (id, encrypted) -> {
                    TocFamilyAccount upd = new TocFamilyAccount();
                    upd.setFamilyAccountId(id);
                    upd.setPhone(encrypted);
                    tocFamilyAccountMapper.updateById(upd);
                });
    }

    /**
     * 通用分批回填：主键升序游标 + LIMIT 500；isEncrypted 跳过；单行失败记日志继续。
     */
    private <T> long backfillPage(ListFetcher<T> fetcher, IdGetter<T> idGetter,
                                  FieldGetter<T> fieldGetter, Updater<T> updater) {
        long processed = 0;
        UUID cursor = null;
        while (true) {
            List<T> rows = fetcher.fetch(cursor);
            if (rows.isEmpty()) {
                break;
            }
            for (T row : rows) {
                String value = fieldGetter.get(row);
                if (value == null || encryptionService.isEncrypted(value)) {
                    continue;
                }
                try {
                    updater.update(idGetter.get(row), encryptionService.encrypt(value));
                    processed++;
                } catch (Exception e) {
                    log.error("存量明文回填失败（跳过该行继续）: id={}", idGetter.get(row), e);
                }
            }
            if (rows.size() < BATCH_SIZE) {
                break;
            }
            cursor = idGetter.get(rows.get(rows.size() - 1));
        }
        return processed;
    }

    @FunctionalInterface
    private interface ListFetcher<T> {
        List<T> fetch(UUID cursor);
    }

    @FunctionalInterface
    private interface IdGetter<T> {
        UUID get(T row);
    }

    @FunctionalInterface
    private interface FieldGetter<T> {
        String get(T row);
    }

    @FunctionalInterface
    private interface Updater<T> {
        void update(UUID id, String encrypted);
    }
}
