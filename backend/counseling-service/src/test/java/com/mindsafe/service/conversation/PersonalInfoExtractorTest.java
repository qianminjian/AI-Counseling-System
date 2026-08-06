package com.mindsafe.service.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PersonalInfoExtractor 纯函数测试（ARCH-001 C1 抽取：4 组正则 + 语气词过滤）。
 * <p>
 * 行为基线：从 ConversationServiceImpl.extractPersonalInfo 原样迁移
 * （零 LLM、纯正则、内容 <2 字符直接返回 null），语义不调整只收敛位置。
 */
class PersonalInfoExtractorTest {

    private final PersonalInfoExtractor extractor = new PersonalInfoExtractor();

    @Nested
    @DisplayName("名字提取")
    class NameTests {

        @Test
        @DisplayName("我叫XX → realName")
        void extractName_woJiao() {
            assertThat(extractor.extract("我叫小明").realName()).isEqualTo("小明");
        }

        @Test
        @DisplayName("我的名字是XX → realName")
        void extractName_woDeMingZiShi() {
            assertThat(extractor.extract("我的名字是小美").realName()).isEqualTo("小美");
        }

        @Test
        @DisplayName("你可以叫我XX → realName")
        void extractName_niKeYiJiaoWo() {
            assertThat(extractor.extract("你可以叫我波波").realName()).isEqualTo("波波");
        }

        @Test
        @DisplayName("语气词/动词误匹配被过滤（我叫是 → null）")
        void extractName_filterStopWords() {
            assertThat(extractor.extract("我叫是").realName()).isNull();
        }

        @Test
        @DisplayName("英文名支持（我叫Tom → Tom）")
        void extractName_english() {
            assertThat(extractor.extract("我叫Tom").realName()).isEqualTo("Tom");
        }
    }

    @Nested
    @DisplayName("年龄提取")
    class AgeTests {

        @Test
        @DisplayName("我今年X岁 → age")
        void extractAge_withJinNian() {
            assertThat(extractor.extract("我今年9岁").age()).isEqualTo("9岁");
        }

        @Test
        @DisplayName("我X岁 → age")
        void extractAge_plain() {
            assertThat(extractor.extract("我8岁").age()).isEqualTo("8岁");
        }

        @Test
        @DisplayName("数字后空格（我10 岁 → 10岁；\s* 覆盖数字与岁之间）")
        void extractAge_withSpace() {
            assertThat(extractor.extract("我10 岁").age()).isEqualTo("10岁");
        }
    }

    @Nested
    @DisplayName("年级提取")
    class GradeTests {

        @Test
        @DisplayName("我在X年级 → grade")
        void extractGrade_zai() {
            assertThat(extractor.extract("我在三年级").grade()).isEqualTo("三年级");
        }

        @Test
        @DisplayName("我上X年级 → grade")
        void extractGrade_shang() {
            assertThat(extractor.extract("我上五年级").grade()).isEqualTo("五年级");
        }

        @Test
        @DisplayName("我读X年级 → grade")
        void extractGrade_du() {
            assertThat(extractor.extract("我读六年级").grade()).isEqualTo("六年级");
        }

        @Test
        @DisplayName("数字年级（我读6年级 → 6年级）")
        void extractGrade_numeric() {
            assertThat(extractor.extract("我读6年级").grade()).isEqualTo("6年级");
        }
    }

    @Nested
    @DisplayName("班级提取")
    class ClassTests {

        @Test
        @DisplayName("我在X班 → className")
        void extractClass_zai() {
            assertThat(extractor.extract("我在二班").className()).isEqualTo("二班");
        }

        @Test
        @DisplayName("我是X班的 → className")
        void extractClass_shi() {
            assertThat(extractor.extract("我是3班的").className()).isEqualTo("3班");
        }

        @Test
        @DisplayName("中英文混合（我在A班 → A班）")
        void extractClass_mixed() {
            assertThat(extractor.extract("我在A班").className()).isEqualTo("A班");
        }
    }

    @Nested
    @DisplayName("多字段与边界")
    class MixedTests {

        @Test
        @DisplayName("一条消息同时提取名字+年龄+年级+班级")
        void extract_allFieldsInOneMessage() {
            PersonalInfoExtractor.ExtractedInfo info = extractor.extract("我叫小明，我今年9岁，我在三年级，我在二班");
            assertThat(info.realName()).isEqualTo("小明");
            assertThat(info.age()).isEqualTo("9岁");
            assertThat(info.grade()).isEqualTo("三年级");
            assertThat(info.className()).isEqualTo("二班");
        }

        @Test
        @DisplayName("无任何匹配 → 全部字段 null")
        void extract_noMatch() {
            PersonalInfoExtractor.ExtractedInfo info = extractor.extract("今天天气真好");
            assertThat(info.realName()).isNull();
            assertThat(info.age()).isNull();
            assertThat(info.grade()).isNull();
            assertThat(info.className()).isNull();
        }

        @Test
        @DisplayName("内容短于 2 字符 → null")
        void extract_tooShort() {
            assertThat(extractor.extract("好")).isNull();
            assertThat(extractor.extract("")).isNull();
            assertThat(extractor.extract(null)).isNull();
        }
    }
}
