package com.mindsafe.api.render;

/**
 * HTML 字段转义工具（F8：从 TeacherController 抽离，B-04 导出 HTML 防 XSS）。
 * <p>
 * & < > 引号全量转义，纯函数可单测。
 */
public final class HtmlEscapeUtil {

    private HtmlEscapeUtil() {
    }

    /** HTML 字段转义（& < > 双引号单引号全量转义；null → 空串） */
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
