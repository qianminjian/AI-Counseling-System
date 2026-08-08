package com.mindsafe.service.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CounselingTimeZone 时区工具单元测试（B-03，doing/80 批次 C）
 * <p>
 * 锁死 Asia/Shanghai 日边界语义：UTC 16:00 是上海次日 00:00——
 * 统计口径与 CSV 导出一致（此前 Instant.truncatedTo(DAYS) 为 UTC 边界，
 * UTC+8 每天 08:00 前数据归入前一天）。
 */
class CounselingTimeZoneTest {

    @Nested
    @DisplayName("startOfDay 上海日边界")
    class StartOfDay {

        @Test
        @DisplayName("UTC 16:00 = 上海次日 00:00，当日开始即该时刻")
        void utc1600IsShanghaiNextDayStart() {
            Instant t = Instant.parse("2026-08-08T16:00:00Z");
            assertThat(CounselingTimeZone.startOfDay(t)).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
        }

        @Test
        @DisplayName("上海上午 10:00（UTC 02:00）→ 当日开始为 UTC 前一日 16:00")
        void shanghaiMorningBelongsToSameDay() {
            Instant t = Instant.parse("2026-08-08T02:00:00Z");
            assertThat(CounselingTimeZone.startOfDay(t)).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }

        @Test
        @DisplayName("上海 00:00 前一刻（UTC 15:59:59）→ 当日开始为 UTC 前一日 16:00")
        void justBeforeMidnight() {
            Instant t = Instant.parse("2026-08-08T15:59:59Z");
            assertThat(CounselingTimeZone.startOfDay(t)).isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }
    }

    @Nested
    @DisplayName("startOfNextDay 次日开始")
    class StartOfNextDay {

        @Test
        @DisplayName("UTC 16:00 的次日开始 = UTC 次日 16:00")
        void nextDay() {
            Instant t = Instant.parse("2026-08-08T16:00:00Z");
            assertThat(CounselingTimeZone.startOfNextDay(t)).isEqualTo(Instant.parse("2026-08-09T16:00:00Z"));
        }
    }

    @Nested
    @DisplayName("dateKey / truncateToDay 分组桶")
    class DateKey {

        @Test
        @DisplayName("UTC 15:59:59 → 上海当日（08-08），UTC 16:00 → 上海次日（08-09）")
        void dateKeyFollowsShanghai() {
            assertThat(CounselingTimeZone.dateKey(Instant.parse("2026-08-08T15:59:59Z"))).isEqualTo("2026-08-08");
            assertThat(CounselingTimeZone.dateKey(Instant.parse("2026-08-08T16:00:00Z"))).isEqualTo("2026-08-09");
        }

        @Test
        @DisplayName("truncateToDay 按上海边界归桶（趋势分组 key 对齐）")
        void truncateGroupsByShanghai() {
            Instant t = Instant.parse("2026-08-08T16:30:00Z");
            assertThat(CounselingTimeZone.truncateToDay(t)).isEqualTo(Instant.parse("2026-08-08T16:00:00Z"));
            assertThat(CounselingTimeZone.truncateToDay(Instant.parse("2026-08-08T02:00:00Z")))
                    .isEqualTo(Instant.parse("2026-08-07T16:00:00Z"));
        }
    }
}
