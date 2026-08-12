package com.mindsafe.api.render;

/**
 * CSV 字段转义工具（F8：从 TeacherController 抽离，纯函数可单测）。
 * <p>
 * 规则：含逗号/引号/换行的值加双引号包裹，内部引号翻倍转义（RFC 4180）。
 */
public final class CsvEscapeUtil {

    private CsvEscapeUtil() {
    }

    /** CSV 字段转义（含逗号/引号/换行的值加双引号包裹） */
    public static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
