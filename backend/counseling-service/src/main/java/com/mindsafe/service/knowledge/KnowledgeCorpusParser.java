package com.mindsafe.service.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首批入库语料 Markdown 解析器（KB-101）
 * <p>
 * 解析 data/knowledge-base/01-首批入库语料_v1.md 的结构化格式：
 * <pre>
 * ### KB-NNN 标题
 * - category: xxx | grade_band: xxx | source_type: xxx | evidence_level: xxx
 * - 来源：xxx；yyy
 * （空行）
 * 正文段落...
 * </pre>
 * 每个 {@code ### KB-NNN} 分节 = 一个文档，映射到
 * {@link KnowledgeBaseService#ingestDocument}（title/category/content/source）。
 * grade_band 现表暂无对应列（KB-102 才落地），随 content 首行保留标注供后续迁移。
 */
public final class KnowledgeCorpusParser {

    /** 条目头：### KB-001 认知扭曲：非黑即白思维 */
    private static final Pattern ENTRY_HEADER = Pattern.compile("^###\\s+(KB-\\d{3})\\s+(.+?)\\s*$");
    /** 元数据行内字段：category: xxx / grade_band: xxx */
    private static final Pattern META_CATEGORY = Pattern.compile("category:\\s*([\\w,]+)");
    private static final Pattern META_GRADE_BAND = Pattern.compile("grade_band:\\s*([\\w,]+)");

    private KnowledgeCorpusParser() {
    }

    /** 解析出的单条语料 */
    public record CorpusEntry(
            String code,        // KB-001
            String title,       // 认知扭曲：非黑即白思维
            String category,    // cbt_technique
            String gradeBand,   // all / mid,high
            String source,      // 来源行内容
            String body) {      // 正文（不含元数据行）

        /** 入库用标题：编号 + 标题，保证全局唯一、可追溯 */
        public String documentTitle() {
            return code + " " + title;
        }

        /** 入库用内容：grade_band 标注随正文首行保留（KB-102 元数据字段落地后迁移） */
        public String documentContent() {
            return "【适用年级段 grade_band: " + gradeBand + "】\n" + body;
        }
    }

    /**
     * 解析语料 Markdown 全文
     *
     * @param markdown 语料文件全文
     * @return 按出现顺序的条目列表（不做任何过滤，危机类过滤由调用方负责）
     */
    public static List<CorpusEntry> parse(String markdown) {
        List<CorpusEntry> entries = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return entries;

        String code = null, title = null, category = null, gradeBand = null, source = null;
        StringBuilder body = new StringBuilder();

        for (String line : markdown.split("\n", -1)) {
            Matcher header = ENTRY_HEADER.matcher(line);
            if (header.matches()) {
                addEntry(entries, code, title, category, gradeBand, source, body);
                code = header.group(1);
                title = header.group(2);
                category = null;
                gradeBand = null;
                source = null;
                body = new StringBuilder();
                continue;
            }
            if (code == null) continue; // 文件头/章节说明，未进入条目

            String trimmed = line.trim();
            // 章节分隔（## 或 ---）结束当前条目
            if (trimmed.startsWith("## ") || trimmed.equals("---")) {
                addEntry(entries, code, title, category, gradeBand, source, body);
                code = null;
                body = new StringBuilder();
                continue;
            }
            if (trimmed.startsWith("- category:")) {
                Matcher cat = META_CATEGORY.matcher(trimmed);
                if (cat.find()) category = cat.group(1);
                Matcher gb = META_GRADE_BAND.matcher(trimmed);
                if (gb.find()) gradeBand = gb.group(1);
                continue;
            }
            if (trimmed.startsWith("- 来源：")) {
                source = trimmed.substring("- 来源：".length()).trim();
                continue;
            }
            if (trimmed.startsWith(">")) continue; // 引用说明块不入正文
            body.append(line).append('\n');
        }
        addEntry(entries, code, title, category, gradeBand, source, body);
        return entries;
    }

    private static void addEntry(List<CorpusEntry> entries, String code, String title,
                                 String category, String gradeBand, String source, StringBuilder body) {
        if (code == null) return;
        String text = body.toString().strip();
        if (text.isEmpty()) return; // 无正文的条目视为无效，跳过
        entries.add(new CorpusEntry(code, title, category,
                gradeBand != null ? gradeBand : "all", source, text));
    }
}
