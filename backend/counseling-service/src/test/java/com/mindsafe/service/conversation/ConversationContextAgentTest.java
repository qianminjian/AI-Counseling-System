package com.mindsafe.service.conversation;

import com.mindsafe.ai.orchestrator.StrategyProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConversationContextAgent 单元测试（CTX-Agent：上下文简报组装）
 * <p>
 * 覆盖：身份简报（realName 优先级/昵称/占位符引导询问）、性别/对话次数/进入心情、
 * 交互能力状态（TTS/唤醒）、情绪旅程（轨迹/趋势四分支/状态机三态/缓解计数）、
 * 会话上下文（滚动摘要/主题线索/记忆相关性重排/联盟续接）、画像注入。
 */
class ConversationContextAgentTest {

    private ConversationContextAgent agent;

    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent = new ConversationContextAgent();
    }

    private SessionState newState(String emotionTag, String gender, int grade) {
        return new SessionState(sessionId, UUID.randomUUID(), UUID.randomUUID(),
                emotionTag, "text", gender, 0.5, grade);
    }

    @Nested
    @DisplayName("身份简报")
    class IdentityBrief {

        @Test
        @DisplayName("realName 最高优先级 + 个人信息全量注入")
        void realNamePriorityWithFullInfo() {
            SessionState s = newState("sad", "male", 3);
            s.setPseudonym("某人");
            s.updatePersonalInfo("realName", "小明");
            s.updatePersonalInfo("age", "9");
            s.updatePersonalInfo("grade", "三年级");
            s.updatePersonalInfo("class", "1班");

            String brief = agent.buildContextBrief(s, null, null, null, 3);

            assertThat(brief).contains("孩子的名字：小明");
            assertThat(brief).contains("真实名字：小明");
            assertThat(brief).contains("年龄：9");
            assertThat(brief).contains("年级：三年级");
            assertThat(brief).contains("班级：1班");
            assertThat(brief).contains("性别：男");
            assertThat(brief).contains("3 年级（约 8-9 岁）");
            assertThat(brief).contains("第 3 次对话（你们是老朋友了）");
            assertThat(brief).contains("进入心情：难过");
        }

        @Test
        @DisplayName("无 realName 时有效昵称生效")
        void meaningfulPseudonym() {
            SessionState s = newState(null, "female", 5);
            s.setPseudonym("小红");

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("昵称：小红（请自然地称呼小红）");
            assertThat(brief).doesNotContain("孩子的名字");
            assertThat(brief).contains("性别：女");
            assertThat(brief).contains("第一次对话（初次见面，先建立信任）");
            assertThat(brief).doesNotContain("进入心情");
        }

        @Test
        @DisplayName("占位符昵称 → 引导 AI 主动询问名字")
        void placeholderPseudonym() {
            SessionState s = newState("happy", null, 2);
            s.setPseudonym("test");

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("还不知道");
            assertThat(brief).contains("你叫什么名字呀");
            assertThat(brief).doesNotContain("昵称：test");
        }

        @Test
        @DisplayName("TTS/唤醒能力状态全分支")
        void interactionCapabilities() {
            SessionState s = newState(null, null, 4);
            s.setTtsMuted(true);
            s.setWakeEnabled(false);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("语音朗读：已关闭");
            assertThat(brief).contains("语音唤醒：已关闭");

            s.setTtsMuted(false);
            s.setWakeEnabled(true);
            String brief2 = agent.buildContextBrief(s, null, null, null, 1);
            assertThat(brief2).contains("语音朗读：已开启");
            assertThat(brief2).contains("语音唤醒：已开启");
        }
    }

    @Nested
    @DisplayName("情绪旅程")
    class EmotionJourney {

        @Test
        @DisplayName("情绪轨迹 + 好转趋势 + 缓解计数")
        void improvingTrend() {
            SessionState s = newState("sad", null, 3);
            s.addEmotionRecord("sad", 0.9);
            s.addEmotionRecord("sad", 0.8);
            s.addEmotionRecord("happy", 0.7);
            s.addEmotionRecord("happy", 0.8);
            s.setReliefCount(2);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("进入时：难过");
            assertThat(brief).contains("→");
            assertThat(brief).contains("正在好转");
            assertThat(brief).contains("连续 2 轮积极回应");
            assertThat(brief).contains("情绪趋稳");
        }

        @Test
        @DisplayName("恶化趋势")
        void worseningTrend() {
            SessionState s = newState("happy", null, 3);
            s.addEmotionRecord("happy", 0.9);
            s.addEmotionRecord("calm", 0.8);
            s.addEmotionRecord("sad", 0.7);
            s.addEmotionRecord("angry", 0.8);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("正在恶化");
        }

        @Test
        @DisplayName("持续低落趋势")
        void sustainedNegativeTrend() {
            SessionState s = newState(null, null, 3);
            s.addEmotionRecord("sad", 0.9);
            s.addEmotionRecord("angry", 0.8);
            s.addEmotionRecord("fearful", 0.7);
            s.addEmotionRecord("sad", 0.8);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("持续低落，需要更多关注");
        }

        @Test
        @DisplayName("持续积极趋势")
        void sustainedPositiveTrend() {
            SessionState s = newState(null, null, 3);
            s.addEmotionRecord("happy", 0.9);
            s.addEmotionRecord("calm", 0.8);
            s.addEmotionRecord("happy", 0.8);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("持续积极，状态良好");
        }

        @Test
        @DisplayName("不足 3 条情绪记录无趋势；空历史无轨迹行")
        void insufficientHistory() {
            SessionState s = newState(null, null, 3);
            s.addEmotionRecord("sad", 0.9);
            s.addEmotionRecord("happy", 0.8);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("情绪轨迹");
            assertThat(brief).doesNotContain("趋势：");

            SessionState empty = newState(null, null, 3);
            String brief2 = agent.buildContextBrief(empty, null, null, null, 1);
            assertThat(brief2).doesNotContain("情绪轨迹");
        }

        @Test
        @DisplayName("状态机 ACTIVATED/CRISIS 标签")
        void emotionStateLabels() {
            SessionState activated = newState(null, null, 3);
            activated.setEmotionState(StrategyProfile.EmotionState.ACTIVATED);
            assertThat(agent.buildContextBrief(activated, null, null, null, 1))
                    .contains("情绪激活中，优先稳定情绪");

            SessionState crisis = newState(null, null, 3);
            crisis.setEmotionState(StrategyProfile.EmotionState.CRISIS);
            assertThat(agent.buildContextBrief(crisis, null, null, null, 1))
                    .contains("危机状态，安全响应模式");
        }
    }

    @Nested
    @DisplayName("会话上下文")
    class SessionContext {

        @Test
        @DisplayName("滚动摘要 + 主题线索（含重复次数）")
        void summaryAndTopics() {
            SessionState s = newState(null, null, 3);
            s.setSessionSummary("学生聊了考试压力");
            s.addTopicHint("考试压力", 2);
            s.addTopicHint("考试压力", 5);
            s.addTopicHint("和好朋友吵架", 3);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("# 本次对话进展");
            assertThat(brief).contains("[滚动摘要] 学生聊了考试压力");
            assertThat(brief).contains("考试压力（第 2 轮提起，出现 2 次）");
            assertThat(brief).contains("和好朋友吵架（第 3 轮提起）");
        }

        @Test
        @DisplayName("记忆按主题相关性重排：相关条目排前")
        void memoryReorderByRelevance() {
            SessionState s = newState(null, null, 3);
            s.addTopicHint("和妈妈的关系", 2);
            String memory = "# 历史记忆\n- 上次喜欢聊篮球\n- 曾提到和妈妈吵架后很难过\n- 喜欢画画";

            String brief = agent.buildContextBrief(s, null, memory, null, 2);

            assertThat(brief.indexOf("和妈妈吵架")).isLessThan(brief.indexOf("篮球"));
            assertThat(brief).contains("- 喜欢画画");
        }

        @Test
        @DisplayName("记忆过短或无主题线索时保持原序")
        void memoryKeptAsIs() {
            SessionState noTopics = newState(null, null, 3);
            String shortMemory = "一条记忆";
            String brief = agent.buildContextBrief(noTopics, null, shortMemory, null, 1);
            assertThat(brief).contains("一条记忆");

            SessionState withTopics = newState(null, null, 3);
            withTopics.addTopicHint("考试", 1);
            String twoLines = "记忆A\n记忆B";
            String brief2 = agent.buildContextBrief(withTopics, null, twoLines, null, 1);
            assertThat(brief2.indexOf("记忆A")).isLessThan(brief2.indexOf("记忆B"));
        }

        @Test
        @DisplayName("记忆无匹配主题时保持原序")
        void memoryNoMatchKeepsOrder() {
            SessionState s = newState(null, null, 3);
            s.addTopicHint("考试", 1);
            String memory = "# 历史记忆\n- 喜欢篮球\n- 喜欢画画\n- 喜欢音乐";

            String brief = agent.buildContextBrief(s, null, memory, null, 1);

            assertThat(brief.indexOf("篮球")).isLessThan(brief.indexOf("画画"));
            assertThat(brief.indexOf("画画")).isLessThan(brief.indexOf("音乐"));
        }

        @Test
        @DisplayName("联盟续接 Prompt 注入")
        void alliancePromptInjected() {
            SessionState s = newState(null, null, 3);

            String brief = agent.buildContextBrief(s, null, null, "上次你们聊到了和同桌的矛盾", 2);

            assertThat(brief).contains("上次你们聊到了和同桌的矛盾");
        }

        @Test
        @DisplayName("空白摘要与主题时不输出进展区块")
        void noProgressSectionWhenEmpty() {
            SessionState s = newState(null, null, 3);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).doesNotContain("# 本次对话进展");
        }
    }

    @Nested
    @DisplayName("画像与杂项")
    class Misc {

        @Test
        @DisplayName("画像 Prompt 非空注入，空白忽略")
        void profilePrompt() {
            SessionState s = newState(null, null, 3);

            assertThat(agent.buildContextBrief(s, "孩子偏好短句回应", null, null, 1))
                    .contains("孩子偏好短句回应");
            assertThat(agent.buildContextBrief(s, "   ", null, null, 1))
                    .doesNotContain("   \n");
        }

        @Test
        @DisplayName("未知情绪标签原样透传")
        void unknownEmotionPassThrough() {
            SessionState s = newState("excited", null, 3);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("进入心情：excited");
        }

        @Test
        @DisplayName("emotionTag 为空时显示未选择")
        void nullEmotionTag() {
            SessionState s = newState(null, null, 3);

            String brief = agent.buildContextBrief(s, null, null, null, 1);

            assertThat(brief).contains("进入时：未选择");
        }
    }
}
