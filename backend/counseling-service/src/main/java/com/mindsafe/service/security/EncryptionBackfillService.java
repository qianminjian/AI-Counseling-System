package com.mindsafe.service.security;

import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.service.security.EncryptedFieldRegistry.BackfillableField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 存量明文加密回填（B-05，2026-08-14；COMP-008 启用配套，frozen/60）
 * <p>
 * ENCRYPTION_ENABLED=true 启动时由 {@link EncryptionBackfillRunner} 触发：对注册表
 * （{@link EncryptedFieldRegistry}，99-7 单一事实源）中全部加密字段的存量明文行做
 * 一次性回填。幂等（isEncrypted 跳过，支持重复执行）。
 * <p>
 * 多租户：租户行级表经 runAsSystem 系统作用域跨租户执行（先例
 * DataRetentionCleanupJob，M1-003 fail-fast 豁免）；平台级表（toc_family_accounts）
 * 在忽略名单。单行失败记日志不阻断；全表扫描后输出汇总报告。
 */
@Service
public class EncryptionBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfillService.class);

    private final FieldEncryptionService encryptionService;
    private final EncryptedFieldRegistry registry;

    public EncryptionBackfillService(FieldEncryptionService encryptionService,
                                     EncryptedFieldRegistry registry) {
        this.encryptionService = encryptionService;
        this.registry = registry;
    }

    /** 回填报告（表 → 处理行数；M3：按注册表派生，新增表自动进入报告） */
    public record BackfillReport(Map<String, Long> counts) {
        public long total() {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** 回填全部加密字段（幂等；仅记录处理数，不抛错） */
    public BackfillReport backfillAll() {
        BackfillReport[] holder = new BackfillReport[1];
        TenantContextHolder.runAsSystem(() -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (BackfillableField<?> field : registry.fields()) {
                counts.put(field.tableName(), backfillField(field));
            }
            // M3：报告与注册表强一致——新增/改名加密字段未同步报告断言即失败（防静默全零）
            if (!counts.keySet().equals(registry.fields().stream()
                    .map(BackfillableField::tableName).collect(java.util.stream.Collectors.toSet()))) {
                throw new IllegalStateException("回填报告与加密字段注册表不一致: " + counts.keySet());
            }
            holder[0] = new BackfillReport(counts);
        });
        return holder[0];
    }

    /**
     * 注册表驱动单循环：主键升序游标 + LIMIT（注册表 BATCH_SIZE）；isEncrypted 跳过；
     * 单行失败记日志继续。
     */
    private <T> long backfillField(BackfillableField<T> field) {
        long processed = 0;
        UUID cursor = null;
        while (true) {
            List<T> rows = field.pageFetcher().apply(cursor);
            if (rows.isEmpty()) {
                break;
            }
            for (T row : rows) {
                String value = field.valueGetter().apply(row);
                if (value == null || encryptionService.isEncrypted(value)) {
                    continue;
                }
                UUID id = field.idGetter().apply(row);
                try {
                    field.valueUpdater().accept(id, encryptionService.encrypt(value));
                    processed++;
                } catch (Exception e) {
                    log.error("存量明文回填失败（跳过该行继续）: table={}, id={}", field.tableName(), id, e);
                }
            }
            if (rows.size() < EncryptedFieldRegistry.BATCH_SIZE) {
                break;
            }
            cursor = field.idGetter().apply(rows.get(rows.size() - 1));
        }
        return processed;
    }
}
