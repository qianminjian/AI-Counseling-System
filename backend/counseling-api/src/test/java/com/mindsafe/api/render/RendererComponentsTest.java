package com.mindsafe.api.render;

import com.mindsafe.service.teacher.TeacherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F8 渲染组件单元测试（CsvEscapeUtil/HtmlEscapeUtil/EmotionLabelSupport 转义表驱动 + 渲染器 HTML + CSV 写器）。
 * <p>
 * 覆盖从 TeacherController 抽离的纯函数：CSV 转义（RFC 4180）、HTML 转义（B-04 防 XSS）、
 * 情绪翻译委托、周报/会话导出 HTML 输出、预警/学生 CSV 写器（BOM + 截断提示）。
 */
class RendererComponentsTest {

    // ===== CSV 转义（RFC 4180） =====

    @Test
    @DisplayName("CSV 转义：普通值原样返回")
    void csvEscape_plainValue() {
        assertThat(CsvEscapeUtil.escape("张三")).isEqualTo("张三");
        assertThat(CsvEscapeUtil.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("CSV 转义：含逗号/引号/换行 → 双引号包裹且内部引号翻倍")
    void csvEscape_specialChars() {
        assertThat(CsvEscapeUtil.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvEscapeUtil.escape("he said \"hi\"")).isEqualTo("\"he said \"\"hi\"\"\"");
        assertThat(CsvEscapeUtil.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
    }

    // ===== HTML 转义（B-04 防 XSS） =====

    @Test
    @DisplayName("HTML 转义：& < > 双引号单引号全量转义")
    void htmlEscape_fullEscape() {
        assertThat(HtmlEscapeUtil.escape("<script>alert('x')</script>"))
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(HtmlEscapeUtil.escape("a&b\"c")).isEqualTo("a&amp;b&quot;c");
        assertThat(HtmlEscapeUtil.escape(null)).isEmpty();
    }

    // ===== 情绪翻译委托（DC-008 单一标签源） =====

    @Test
    @DisplayName("情绪翻译：已知码值 → 中文标签；未知码值 → 原值")
    void emotionLabel_knownAndUnknown() {
        assertThat(EmotionLabelSupport.labelOf("anxious")).isEqualTo("紧张");
        assertThat(EmotionLabelSupport.labelOf("unknown-code")).isEqualTo("unknown-code");
    }

    // ===== 周报 HTML 渲染 =====

    @Test
    @DisplayName("周报渲染：包含标题/统计/分布表，班级名转义，情绪中文翻译")
    void weeklyReport_rendersEscapedHtml() {
        TeacherService.StatsVO stats = new TeacherService.StatsVO(
                List.of(new TeacherService.RiskDistItem(2, "中风险", 3)),
                List.of(new TeacherService.ClassRiskItem("1班<b>", 2, 5)),
                List.of(new TeacherService.DailyCount("2026-08-11", 4)),
                List.of(new TeacherService.EmotionItem("anxious", 6)));

        String html = WeeklyReportRenderer.render(stats, "2026-08-12");

        assertThat(html).contains("<title>心理辅导周报 - 2026-08-12</title>");
        assertThat(html).contains("预警总数");
        assertThat(html).contains("1班&lt;b&gt;");   // HTML 转义
        assertThat(html).contains("紧张");           // 情绪中文翻译
        assertThat(html).contains(">6<");            // 情绪次数
    }

    // ===== 会话存档 HTML 渲染 =====

    @Test
    @DisplayName("会话导出渲染：学生/AI 分色，内容与情绪标签转义")
    void sessionExport_rendersEscapedHtml() {
        UUID sessionId = UUID.randomUUID();
        List<TeacherService.MessageSummaryVO> messages = List.of(
                new TeacherService.MessageSummaryVO(UUID.randomUUID(), "student", 1, "今天有点紧张<script>", "anxious", 2, Instant.now()),
                new TeacherService.MessageSummaryVO(UUID.randomUUID(), "ai", 2, "试着深呼吸", null, 0, Instant.now()));

        String html = SessionExportRenderer.render(sessionId, messages, "2026-08-12 10:30");

        assertThat(html).contains("<title>会话记录 - " + sessionId + "</title>");
        assertThat(html).contains("class='msg student'");
        assertThat(html).contains("class='msg ai'");
        assertThat(html).contains("今天有点紧张&lt;script&gt;");  // HTML 转义
        assertThat(html).contains("· 紧张");                       // 情绪翻译
        assertThat(html).contains("2026-08-12 10:30");
    }

    // ===== CSV 写器 =====

    @Test
    @DisplayName("预警 CSV：BOM + 表头 + 行渲染（转义生效）+ 未达上限无截断提示")
    void csvWriter_alerts_headersAndRows() {
        List<TeacherService.AlertVO> alerts = List.of(new TeacherService.AlertVO(
                UUID.randomUUID(), UUID.randomUUID(), "小明,同学", "自伤", 2, "open",
                Instant.parse("2026-08-11T10:30:00Z"), UUID.randomUUID(), false));
        StringWriter sw = new StringWriter();

        CsvExportWriter.writeAlerts(new PrintWriter(sw), alerts, 5000);

        String csv = sw.toString();
        assertThat(csv).startsWith("\uFEFF学生,风险类型,风险等级,状态,检测时间,处理人");
        assertThat(csv).contains("\"小明,同学\"");      // 逗号转义
        assertThat(csv).contains("2026-08-11 18:30");   // Asia/Shanghai 时区
        assertThat(csv).doesNotContain("# 提示");
    }

    @Test
    @DisplayName("预警 CSV：达上限 → 显式截断提示")
    void csvWriter_alerts_hardLimitNotice() {
        TeacherService.AlertVO alert = new TeacherService.AlertVO(
                UUID.randomUUID(), UUID.randomUUID(), "小明", "自伤", 2, "open",
                Instant.now(), UUID.randomUUID(), false);
        StringWriter sw = new StringWriter();

        CsvExportWriter.writeAlerts(new PrintWriter(sw), List.of(alert), 1);

        assertThat(sw.toString()).contains("# 提示：预警记录达到导出上限 1 条");
    }

    @Test
    @DisplayName("学生 CSV：BOM + 表头 + 昵称/班级转义")
    void csvWriter_students_escaped() {
        StringWriter sw = new StringWriter();

        CsvExportWriter.writeStudents(new PrintWriter(sw),
                List.of(new CsvExportWriter.StudentRow("小,红", "G7", "1班", "active")));

        String csv = sw.toString();
        assertThat(csv).startsWith("\uFEFF昵称,年级,班级,状态");
        assertThat(csv).contains("\"小,红\"");
        assertThat(csv).contains("1班");
    }
}
