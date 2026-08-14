package com.mindsafe.service.security;

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

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * EncryptedFieldRegistry 单元测试（99-7，2026-08-14）——加密字段清单单一事实源断言。
 * <p>
 * 字段清单变更（新增/改名/删除加密字段）必须同步本测试与对应迁移容量——
 * 清单失配即测试失败，防止"加密字段"概念散落失实。
 */
class EncryptedFieldRegistryTest {

    private EncryptedFieldRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EncryptedFieldRegistry(
                mock(MessageSummaryMapper.class),
                mock(CounselingSessionMapper.class),
                mock(TeacherNoteMapper.class),
                mock(TocFamilyAccountMapper.class));
    }

    @Test
    @DisplayName("99-7：注册表字段清单与 B-05 盘点一致（4 表 4 列）")
    void fieldInventoryMatches() {
        List<EncryptedFieldRegistry.BackfillableField<?>> fields = registry.fields();

        assertThat(fields).hasSize(4);
        assertThat(fields.stream().map(f -> f.tableName() + "." + f.columnName()).collect(Collectors.toList()))
                .containsExactly(
                        "message_summaries.content_summary",
                        "counseling_sessions.session_summary",
                        "teacher_notes.content",
                        "toc_family_accounts.phone");
    }

    @Test
    @DisplayName("99-7：每字段访问器完整（id/值 getter + 更新器非空）")
    void accessorsComplete() {
        for (EncryptedFieldRegistry.BackfillableField<?> field : registry.fields()) {
            assertThat(field.idGetter()).isNotNull();
            assertThat(field.valueGetter()).isNotNull();
            assertThat(field.valueUpdater()).isNotNull();
            assertThat(field.pageFetcher()).isNotNull();
        }
    }

    @Test
    @DisplayName("99-7：BATCH_SIZE 与回填循环分页一致（500）")
    void batchSizeConsistent() {
        assertThat(EncryptedFieldRegistry.BATCH_SIZE).isEqualTo(500);
    }

    @Test
    @DisplayName("99-7：实体访问器类型正确（编译期类型安全验证）")
    void accessorTyping() {
        // 方法引用编译通过即证明类型安全（无需反射）
        EncryptedFieldRegistry.BackfillableField<MessageSummary> msg =
                (EncryptedFieldRegistry.BackfillableField<MessageSummary>) registry.fields().get(0);
        MessageSummary empty = new MessageSummary();
        assertThat(msg.valueGetter().apply(empty)).isNull(); // 空实体取值安全
        assertThat(msg.idGetter().apply(empty)).isNull();
    }
}
