package com.mindsafe.service.common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 业务时区工具（B-03，doing/80 批次 C）
 * <p>
 * 统计口径统一 Asia/Shanghai 日边界——与 CSV 导出口径一致。
 * 此前 {@code Instant.truncatedTo(ChronoUnit.DAYS)} 为 UTC 日边界，
 * UTC+8 每天 08:00 前数据归入前一天（跨日统计漂移）。
 */
public final class CounselingTimeZone {

    /** 业务权威时区（中国标准时间，无夏令时） */
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private CounselingTimeZone() {
    }

    /** 上海时区当日开始（UTC 16:00 为上海次日 00:00） */
    /** 今日（业务时区）LocalDate——doing/92 R-010 收敛口 */
    public static java.time.LocalDate today() {
        return java.time.LocalDate.now(SHANGHAI);
    }

    /** 今日起始（业务时区）Instant——doing/92 R-010 收敛口 */
    public static Instant todayStart() {
        return today().atStartOfDay(SHANGHAI).toInstant();
    }

    public static Instant startOfDay(Instant now) {
        return now.atZone(SHANGHAI).toLocalDate().atStartOfDay(SHANGHAI).toInstant();
    }

    /** 上海时区次日开始 */
    public static Instant startOfNextDay(Instant now) {
        return startOfDay(now).plus(1, ChronoUnit.DAYS);
    }

    /** 按上海时区归日桶（趋势分组 key） */
    public static Instant truncateToDay(Instant t) {
        return t.atZone(SHANGHAI).toLocalDate().atStartOfDay(SHANGHAI).toInstant();
    }

    /** 上海时区日期串（yyyy-MM-dd，DailyCount 展示 key） */
    public static String dateKey(Instant t) {
        return t.atZone(SHANGHAI).toLocalDate().toString();
    }
}
