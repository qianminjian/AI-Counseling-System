package com.mindsafe.service.knowledge;

import com.mindsafe.service.knowledge.KnowledgeCorpusParser.CorpusEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 语料 Markdown 解析器测试（KB-101）
 * <p>
 * 覆盖：合成语料的结构解析 + 真实语料文件（62 条）的全量解析校验。
 */
class KnowledgeCorpusParserTest {

    /** 真实语料文件（相对 counseling-service 模块目录） */
    private static final Path CORPUS_FILE =
            Path.of("../../data/knowledge-base/01-首批入库语料_v1.md");

    private static final String SAMPLE = """
            # 知识库首批入库语料 v1
            > 说明头，不应被解析为条目内容

            ## 一、CBT 技术库（cbt_technique，2 条）

            ### KB-001 认知扭曲：非黑即白思维
            - category: cbt_technique | grade_band: all | source_type: textbook | evidence_level: 高
            - 来源：Beck 认知扭曲分类；design/15 §3.1

            非黑即白思维指用两个极端看事情。

            ### KB-005 认知扭曲：情绪推理
            - category: cbt_technique | grade_band: mid,high | source_type: textbook | evidence_level: 高
            - 来源：认知行为疗法公开经典知识

            情绪推理指把感觉当事实。
            第二段正文。

            ---

            ## 五、危机干预库（crisis_intervention，1 条）⚠️ 仅教师侧

            > 铁律：本节不进学生对话 RAG。

            ### KB-053 儿童心理危机风险信号识别（仅教师侧）
            - category: crisis_intervention | grade_band: all | source_type: official | evidence_level: 高
            - 来源：WHO 公开心理健康资料

            需要警觉的信号说明。
            """;

    @Nested
    @DisplayName("合成语料结构解析")
    class SyntheticParsing {

        @Test
        @DisplayName("解析出全部条目，编号/标题/分类/年级段/来源正确")
        void parsesEntriesWithMetadata() {
            List<CorpusEntry> entries = KnowledgeCorpusParser.parse(SAMPLE);

            assertThat(entries).hasSize(3);

            CorpusEntry first = entries.get(0);
            assertThat(first.code()).isEqualTo("KB-001");
            assertThat(first.title()).isEqualTo("认知扭曲：非黑即白思维");
            assertThat(first.category()).isEqualTo("cbt_technique");
            assertThat(first.gradeBand()).isEqualTo("all");
            assertThat(first.source()).isEqualTo("Beck 认知扭曲分类；design/15 §3.1");
            assertThat(first.body()).isEqualTo("非黑即白思维指用两个极端看事情。");

            CorpusEntry second = entries.get(1);
            assertThat(second.gradeBand()).isEqualTo("mid,high");
            assertThat(second.body()).contains("第二段正文。");

            CorpusEntry crisis = entries.get(2);
            assertThat(crisis.code()).isEqualTo("KB-053");
            assertThat(crisis.category()).isEqualTo("crisis_intervention");
        }

        @Test
        @DisplayName("入库标题带 KB 编号前缀；正文首行保留 grade_band 标注")
        void documentTitleAndContentMapping() {
            CorpusEntry entry = KnowledgeCorpusParser.parse(SAMPLE).get(1);

            assertThat(entry.documentTitle()).isEqualTo("KB-005 认知扭曲：情绪推理");
            assertThat(entry.documentContent())
                    .startsWith("【适用年级段 grade_band: mid,high】\n")
                    .contains("情绪推理指把感觉当事实。");
        }

        @Test
        @DisplayName("元数据行/引用说明块不混入正文")
        void metadataAndQuoteLinesExcludedFromBody() {
            List<CorpusEntry> entries = KnowledgeCorpusParser.parse(SAMPLE);

            for (CorpusEntry entry : entries) {
                assertThat(entry.body()).doesNotContain("category:");
                assertThat(entry.body()).doesNotContain("- 来源：");
                assertThat(entry.body()).doesNotContain("铁律");
            }
        }

        @Test
        @DisplayName("空输入返回空列表")
        void emptyInputReturnsEmpty() {
            assertThat(KnowledgeCorpusParser.parse(null)).isEmpty();
            assertThat(KnowledgeCorpusParser.parse("")).isEmpty();
            assertThat(KnowledgeCorpusParser.parse("# 只有标题没有条目")).isEmpty();
        }
    }

    @Nested
    @DisplayName("真实语料文件全量校验")
    class RealCorpusValidation {

        @Test
        @DisplayName("62 条全部解析成功，分类分布与文件头声明一致")
        void parsesAll62Entries() throws IOException {
            assumeTrue(Files.exists(CORPUS_FILE), "语料文件不存在，跳过（CI 裁剪场景）");
            String markdown = Files.readString(CORPUS_FILE);

            List<CorpusEntry> entries = KnowledgeCorpusParser.parse(markdown);

            assertThat(entries).hasSize(62);
            // 分类分布：cbt 15 / emotion 15 / development 12 / social 10 / crisis 10
            assertThat(entries).filteredOn(e -> e.category().equals("cbt_technique")).hasSize(15);
            assertThat(entries).filteredOn(e -> e.category().equals("emotion_regulation")).hasSize(15);
            assertThat(entries).filteredOn(e -> e.category().equals("development_psychology")).hasSize(12);
            assertThat(entries).filteredOn(e -> e.category().equals("social_skills")).hasSize(10);
            assertThat(entries).filteredOn(e -> e.category().equals("crisis_intervention")).hasSize(10);
            // 每条都有完整字段
            for (CorpusEntry entry : entries) {
                assertThat(entry.code()).matches("KB-\\d{3}");
                assertThat(entry.title()).isNotBlank();
                assertThat(entry.category()).isNotBlank();
                assertThat(entry.source()).isNotBlank();
                assertThat(entry.body()).isNotBlank();
            }
        }

        @Test
        @DisplayName("危机类条目恰为 KB-053~062（缓入范围核验）")
        void crisisEntriesAreExactlyKb053To062() throws IOException {
            assumeTrue(Files.exists(CORPUS_FILE), "语料文件不存在，跳过（CI 裁剪场景）");
            String markdown = Files.readString(CORPUS_FILE);

            List<String> crisisCodes = KnowledgeCorpusParser.parse(markdown).stream()
                    .filter(e -> e.category().equals("crisis_intervention"))
                    .map(CorpusEntry::code)
                    .toList();

            assertThat(crisisCodes).containsExactly(
                    "KB-053", "KB-054", "KB-055", "KB-056", "KB-057",
                    "KB-058", "KB-059", "KB-060", "KB-061", "KB-062");
        }
    }
}
