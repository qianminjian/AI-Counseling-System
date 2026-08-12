package com.mindsafe.api.render;

import com.mindsafe.domain.entity.User;
import com.mindsafe.service.teacher.TeacherService;

import java.util.List;
import java.util.UUID;

/**
 * 会话记录导出 HTML 渲染器（F8：从 TeacherController.exportSession 抽离，纯函数可单测）。
 * <p>
 * 输出可打印的个案存档 HTML；消息内容与情绪标签经 HTML 转义（B-04 防 XSS），
 * 学生/AI 消息分色渲染。
 */
public final class SessionExportRenderer {

    private SessionExportRenderer() {
    }

    /** 渲染会话存档 HTML（exportedAt 为导出时刻文本，如 2026-08-12 10:30） */
    public static String render(UUID sessionId, List<TeacherService.MessageSummaryVO> messages, String exportedAt) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<title>会话记录 - ").append(sessionId).append("</title>");
        html.append("<style>body{font-family:'PingFang SC',sans-serif;padding:40px;color:#333;max-width:700px;margin:0 auto}");
        html.append("h1{font-size:18px;border-bottom:2px solid #1890ff;padding-bottom:8px}");
        html.append(".msg{margin:12px 0;padding:10px 14px;border-radius:8px;font-size:13px;line-height:1.6}");
        html.append(".student{background:#e6f7ff;margin-left:40px}.ai{background:#f6ffed;margin-right:40px}");
        html.append(".meta{font-size:11px;color:#999;margin-bottom:4px}");
        html.append("@media print{body{padding:20px}}</style></head><body>");
        html.append("<h1>🛡️ MindSafe 会话记录（个案存档）</h1>");
        html.append("<p style='color:#999;font-size:12px'>会话 ID：").append(sessionId).append(" | 导出时间：")
            .append(exportedAt).append("</p><hr>");

        for (var msg : messages) {
            boolean isStudent = User.USER_TYPE_STUDENT.equals(msg.senderType());
            html.append("<div class='msg ").append(isStudent ? "student" : "ai").append("'>");
            html.append("<div class='meta'>").append(isStudent ? "🧒 学生" : "🤖 AI");
            if (msg.emotionLabel() != null) html.append(" · ").append(EmotionLabelSupport.labelOf(msg.emotionLabel()));
            html.append("</div>");
            html.append("<div>").append(HtmlEscapeUtil.escape(msg.contentSummary())).append("</div>");
            html.append("</div>");
        }

        html.append("<p style='margin-top:32px;color:#bbb;font-size:11px'>—— MindSafe AI 心理辅导系统 · 机密文件 ——</p>");
        html.append("</body></html>");
        return html.toString();
    }
}
