package com.mindsafe.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 存量明文加密回填触发器（B-05，2026-08-14；COMP-008 启用配套，frozen/60）
 * <p>
 * 仅在 ENCRYPTION_ENABLED=true 时装配执行（ApplicationRunner，Flyway 迁移完成后
 * 运行）：调用 {@link EncryptionBackfillService#backfillAll()} 将存量明文一次性
 * 回填为密文。幂等——重复启动/轮换密钥后再次执行均安全。
 */
@Component
@ConditionalOnProperty(name = "mindsafe.encryption.enabled", havingValue = "true")
public class EncryptionBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfillRunner.class);

    private final EncryptionBackfillService backfillService;

    public EncryptionBackfillRunner(EncryptionBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        EncryptionBackfillService.BackfillReport report = backfillService.backfillAll();
        log.info("存量明文加密回填完成: messageSummaries={}, sessionSummaries={}, teacherNotes={}, familyPhones={}, total={}",
                report.messageSummaries(), report.sessionSummaries(), report.teacherNotes(), report.familyPhones(),
                report.total());
    }
}
