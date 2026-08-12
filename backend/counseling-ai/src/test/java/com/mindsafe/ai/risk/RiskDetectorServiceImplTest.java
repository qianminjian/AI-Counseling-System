package com.mindsafe.ai.risk;

import com.mindsafe.common.dto.risk.RiskDetectionResult;
import com.mindsafe.common.enums.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RiskDetectorServiceImpl 单元测试
 * <p>
 * 覆盖：红色硬规则、橙色预警、黄色关注、否定/误报控制、安全消息。
 */
class RiskDetectorServiceImplTest {

    private RiskDetectorServiceImpl riskDetector;

    @BeforeEach
    void setUp() {
        riskDetector = new RiskDetectorServiceImpl(new RiskKeywordRegistry());
    }

    @Nested
    @DisplayName("红色硬规则（强制升级）")
    class RedHardRules {

        @ParameterizedTest
        @ValueSource(strings = {
                "我不想活了",
                "我想跳楼",
                "我想割腕",
                "活着没意思",
                "我想上吊",
                "我写了遗书",
                "我想去死",
                "我要自杀",
                "我要杀了自己",
                "我想结束生命"
        })
        @DisplayName("自伤/自杀关键词 → 红色")
        void should_return_red_when_self_harm_keywords(String message) {
            RiskDetectionResult result = riskDetector.detect(message);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.hardUpgrade()).isTrue();
            assertThat(result.matchedKeywords()).isNotEmpty();
            assertThat(result.shouldNotifyTeacher()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "我要带刀去学校",
                "我要放火烧学校",
                "我要炸了学校"
        })
        @DisplayName("他伤/暴力关键词 → 红色")
        void should_return_red_when_violence_keywords(String message) {
            RiskDetectionResult result = riskDetector.detect(message);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.hardUpgrade()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "他摸我隐私部位",
                "他让我发裸照",
                "他摸我隐私部位了"
        })
        @DisplayName("性侵关键词 → 红色")
        void should_return_red_when_sexual_abuse_keywords(String message) {
            RiskDetectionResult result = riskDetector.detect(message);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.hardUpgrade()).isTrue();
        }
    }

    @Nested
    @DisplayName("橙色预警")
    class OrangeRisk {

        @ParameterizedTest
        @ValueSource(strings = {
                "不想活了，太累了",
                "死了算了",
                "活着很累，没希望了",
                "我要杀了他",
                "我要打死他",
                "我要报复",
                "我被打了，每天都被打",
                "我被孤立了",
                "他们勒索我",
                "爸爸把我赶出家门",
                "妈妈把我锁屋里",
                "我想离家出走",
                "我喘不过气来",
                "我要崩溃了",
                // F-2：高频"被X欺负"中间插词变体（原"被欺负"不命中致漏报）
                "我最近总被同学欺负，很难受",
                "我被人欺负了，不敢告诉老师"
        })
        @DisplayName("橙色关键词 → 橙色或红色")
        void should_return_orange_or_higher_when_medium_risk_keywords(String message) {
            RiskDetectionResult result = riskDetector.detect(message);

            // 部分关键词可能触发红色硬规则（如「不想活」）
            assertThat(result.level().severity()).isGreaterThanOrEqualTo(RiskLevel.ORANGE.severity());
            assertThat(result.shouldNotifyTeacher()).isTrue();
        }
    }

    @Nested
    @DisplayName("黄色关注")
    class YellowRisk {

        @ParameterizedTest
        @ValueSource(strings = {
                "我最近很难过",
                "我每天哭",
                "我睡不着",
                "我吃不下饭",
                "我被骂了",
                "我最近每天哭",
                "我头痛",
                "我不想上学"
        })
        @DisplayName("黄色关键词 → 黄色")
        void should_return_yellow_when_low_risk_keywords(String message) {
            RiskDetectionResult result = riskDetector.detect(message);

            assertThat(result.level()).isEqualTo(RiskLevel.YELLOW);
            assertThat(result.shouldNotifyTeacher()).isFalse();
        }
    }

    @Nested
    @DisplayName("否定/误报控制")
    class FalsePositiveControl {

        @ParameterizedTest
        @ValueSource(strings = {
                "我不想死，我只是很难过",
                "我没有想死",
                "我不会自杀的",
                "我不想自杀，别担心",
                "我不会去跳楼的",
                "我不想割腕"
        })
        @DisplayName("自杀否定表达 → 仍触发红色（design/04 §九铁律：RED 不可被否定降级）")
        void should_trigger_red_even_with_negation(String message) {
            // 否定降噪仅适用于橙/黄档；自伤否定表达本身就是高风险信号（"我不想死"→自杀念头已在意识中）
            RiskDetectionResult result = riskDetector.detect(message);

            assertThat(result.level()).isEqualTo(RiskLevel.RED);
            assertThat(result.hardUpgrade()).isTrue();
            assertThat(result.shouldNotifyTeacher()).isTrue();
        }

        @Test
        @DisplayName("引用语境：'故事里有人跳楼' 仍触发红色（硬规则不可降级）")
        void should_still_trigger_red_even_in_story_context() {
            RiskDetectionResult result = riskDetector.detect("故事里有人跳楼了，好可怕");

            // design/04 原则：红色硬规则不可被自动降级
            assertThat(result.level()).isEqualTo(RiskLevel.RED);
        }

        @Test
        @DisplayName("游戏语境：'游戏里放火' 仍触发红色（硬规则不可降级）")
        void should_still_trigger_red_even_in_game_context() {
            RiskDetectionResult result = riskDetector.detect("游戏里放火了，好好玩");

            // design/04 原则：红色硬规则不可被自动降级
            assertThat(result.level()).isEqualTo(RiskLevel.RED);
        }

        @Test
        @DisplayName("橙色关键词 + 否定语境 → 降为黄色")
        void should_downgrade_orange_when_negation() {
            RiskDetectionResult result = riskDetector.detect("我没有离家出走，只是说说而已");

            // 「离家出走」在橙色列表，但「没有」否定前缀使其降为黄色
            assertThat(result.level().severity()).isLessThanOrEqualTo(RiskLevel.YELLOW.severity());
        }
    }

    @Nested
    @DisplayName("安全消息")
    class SafeMessages {

        @ParameterizedTest
        @ValueSource(strings = {
                "今天考试考了100分！",
                "我和好朋友一起玩了",
                "妈妈给我做了红烧肉",
                "明天要去春游了",
                "老师表扬了我",
                "",
                "   "
        })
        @DisplayName("普通消息 → 绿色安全")
        void should_return_green_when_normal_message(String message) {
            RiskDetectionResult result = riskDetector.detect(message);

            assertThat(result.level()).isEqualTo(RiskLevel.GREEN);
            assertThat(result.isRisky()).isFalse();
        }

        @Test
        @DisplayName("null 消息 → 安全")
        void should_return_safe_when_null() {
            RiskDetectionResult result = riskDetector.detect(null);

            assertThat(result.level()).isEqualTo(RiskLevel.GREEN);
        }
    }

    @Nested
    @DisplayName("分类准确性")
    class CategoryAccuracy {

        @Test
        @DisplayName("自伤关键词归类为'自伤/自杀'")
        void should_categorize_self_harm() {
            RiskDetectionResult result = riskDetector.detect("我想跳楼");

            assertThat(result.category()).isEqualTo("自伤/自杀");
        }

        @Test
        @DisplayName("霸凌关键词归类为'霸凌/网络欺凌'")
        void should_categorize_bullying() {
            RiskDetectionResult result = riskDetector.detect("我被孤立了，没人理我");

            assertThat(result.category()).isEqualTo("霸凌/网络欺凌");
        }

        @Test
        @DisplayName("家暴关键词归类为'家庭虐待/忽视'")
        void should_categorize_domestic_abuse() {
            RiskDetectionResult result = riskDetector.detect("爸爸把我赶出家门了");

            assertThat(result.category()).isEqualTo("家庭虐待/忽视");
        }
    }
}
