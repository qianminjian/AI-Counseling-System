package com.mindsafe.api.render;

import com.mindsafe.service.teacher.TeacherService;

/**
 * 周报 HTML 渲染器（F8：从 TeacherController.weeklyReport 抽离，纯函数可单测）。
 * <p>
 * 输出教师浏览器 Ctrl+P 可打印的周报 HTML（风隩分布/班级对比/情绪分布），
 * 班级名与情绪标签经 HTML 转义（B-04 防 XSS）。
 */
public final class WeeklyReportRenderer {

    private WeeklyReportRenderer() {
    }

    /** 渲染周报 HTML（reportDate 为 yyyy-MM-dd 报告日期，与原标题保持一致） */
    public static String render(TeacherService.StatsVO stats, String reportDate) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<title>心理辅导周报 - ").append(reportDate).append("</title>");
        html.append("<style>body{font-family:'PingFang SC',sans-serif;padding:40px;color:#333}");
        html.append("h1{font-size:22px;border-bottom:2px solid #1890ff;padding-bottom:8px}");
        html.append("table{width:100%;border-collapse:collapse;margin:16px 0}");
        html.append("th,td{border:1px solid #ddd;padding:8px 12px;text-align:left;font-size:13px}");
        html.append("th{background:#f5f7fa}.stat{display:inline-block;margin:0 24px 12px 0}");
        html.append(".stat b{font-size:28px;color:#1890ff;display:block}.stat span{font-size:12px;color:#999}");
        html.append("@media print{body{padding:20px}}</style></head><body>");
        html.append("<h1>🧠 AI 心理辅导系统 — 周报</h1>");
        html.append("<p style='color:#999;font-size:12px'>报告日期：").append(reportDate).append("</p>");

        // 概览统计
        html.append("<div>");
        long totalAlerts = stats.riskDistribution().stream().mapToLong(TeacherService.RiskDistItem::count).sum();
        html.append("<div class='stat'><b>").append(totalAlerts).append("</b><span>预警总数</span></div>");
        html.append("<div class='stat'><b>").append(stats.sessionTrend().size()).append("</b><span>活跃天数</span></div>");
        long totalSessions = stats.sessionTrend().stream().mapToLong(TeacherService.DailyCount::count).sum();
        html.append("<div class='stat'><b>").append(totalSessions).append("</b><span>会话总数</span></div>");
        html.append("</div>");

        // 风隩分布表
        html.append("<h3>风隩分布</h3><table><tr><th>等级</th><th>数量</th></tr>");
        for (var item : stats.riskDistribution()) {
            html.append("<tr><td>").append(item.label()).append("</td><td>").append(item.count()).append("</td></tr>");
        }
        html.append("</table>");

        // 班级对比表
        html.append("<h3>班级对比</h3><table><tr><th>班级</th><th>预警数</th><th>学生数</th></tr>");
        for (var item : stats.classComparison()) {
            html.append("<tr><td>").append(HtmlEscapeUtil.escape(item.classCode())).append("</td><td>")
                .append(item.alertCount()).append("</td><td>").append(item.studentCount()).append("</td></tr>");
        }
        html.append("</table>");

        // 情绪分布
        html.append("<h3>情绪分布</h3><table><tr><th>情绪</th><th>次数</th></tr>");
        for (var item : stats.emotionDistribution()) {
            html.append("<tr><td>").append(EmotionLabelSupport.labelOf(item.emotion())).append("</td><td>").append(item.count()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<p style='margin-top:32px;color:#bbb;font-size:11px'>—— MindSafe AI 心理辅导系统自动生成 ——</p>");
        html.append("</body></html>");
        return html.toString();
    }
}
