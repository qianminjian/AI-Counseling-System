package com.mindsafe.api.render;

import com.mindsafe.service.teacher.TeacherService;

import java.io.PrintWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV 导出写器（F8：从 TeacherController 导出端点抽离，纯函数可单测）。
 * <p>
 * 统一处理：UTF-8 BOM（Excel 中文兼容）、显式截断提示、表头、行渲染（CSV 转义）。
 */
public final class CsvExportWriter {

    /** 导出时间格式（Asia/Shanghai，与列表展示一致） */
    private static final DateTimeFormatter CSV_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private CsvExportWriter() {
    }

    /**
     * 预警记录导出行（教师端可见字段，不含内部字段）。
     */
    public record StudentRow(String pseudonym, String gradeCode, String classCode, String status) {
    }

    /**
     * 写预警记录 CSV（先取 Writer 再写 BOM，避免 getOutputStream/getWriter 混用抛 IllegalStateException）。
     *
     * @param hardLimit 导出上限（达上限时输出显式截断提示）
     */
    public static void writeAlerts(PrintWriter writer, List<TeacherService.AlertVO> alerts, int hardLimit) {
        writer.print('\uFEFF');
        // B-01：显式截断提示（不再静默）
        if (alerts.size() >= hardLimit) {
            writer.println("# 提示：预警记录达到导出上限 " + hardLimit + " 条，数据已截断，请缩小范围后分批导出");
        }
        writer.println("学生,风险类型,风险等级,状态,检测时间,处理人");
        for (var a : alerts) {
            writer.printf("%s,%s,%d,%s,%s,%s%n",
                    CsvEscapeUtil.escape(a.studentName()), CsvEscapeUtil.escape(a.riskType()), a.riskLevel(),
                    CsvEscapeUtil.escape(a.status()),
                    a.detectedAt() != null ? CSV_DATE_FMT.format(a.detectedAt()) : "",
                    a.assignedUserId() != null ? a.assignedUserId().toString().substring(0, 8) : "");
        }
        writer.flush();
    }

    /** 写学生列表 CSV（昵称/年级/班级/状态，均经 CSV 转义） */
    public static void writeStudents(PrintWriter writer, List<StudentRow> students) {
        writer.print('\uFEFF');
        writer.println("昵称,年级,班级,状态");
        for (var s : students) {
            writer.printf("%s,%s,%s,%s%n",
                    CsvEscapeUtil.escape(s.pseudonym()), CsvEscapeUtil.escape(s.gradeCode()),
                    CsvEscapeUtil.escape(s.classCode()), CsvEscapeUtil.escape(s.status()));
        }
        writer.flush();
    }
}
