package com.mindsafe.ai.chat;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.ai.safety.OutputContentFilter;
import com.mindsafe.ai.safety.OutputReviewService;
import com.mindsafe.common.dto.chat.StreamMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 聊天服务实现（Spring AI ChatClient 流式调用 + 多轮对话记忆 + 双层输出安全审查）
 * <p>
 * System Prompt 从 classpath 模板文件加载（SYS-001），运行时注入 emotion_tag 等变量。
 * 输出安全：Layer1 {@link OutputContentFilter} 流式实时硬过滤（命中即中断+安全话术）；
 * Layer2 {@link OutputReviewService} 流结束后异步 SAF-002 语义审查（检测+留痕，不阻塞）。
 */
@Service
public class AiChatServiceImpl implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final OutputContentFilter outputContentFilter;
    private final OutputReviewService outputReviewService;
    private final PromptTemplateService promptTemplateService;

    public AiChatServiceImpl(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                             OutputContentFilter outputContentFilter, OutputReviewService outputReviewService,
                             PromptTemplateService promptTemplateService) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.outputContentFilter = outputContentFilter;
        this.outputReviewService = outputReviewService;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public Flux<StreamMessageEvent> chat(UUID sessionId, String emotionTag, String message, String gender, String profilePrompt, int grade) {
        String conversationId = sessionId.toString();
        log.debug("AI 对话请求: sessionId={}, emotion={}, gender={}, grade={}, msgLength={}", sessionId, emotionTag, gender, grade, message.length());

        // 1. 保存用户消息到记忆
        chatMemory.add(conversationId, List.of(new UserMessage(message)));

        // 2. 获取历史消息构建上下文（窗口大小由 MessageWindowChatMemory 配置控制）
        List<Message> history = chatMemory.get(conversationId);

        // 3. 从模板文件加载 System Prompt（SYS-001），注入运行时变量（PROF-010：真实年级替代硬编码）
        String gradeLevel = grade <= 2 ? "1-2" : grade <= 4 ? "3-4" : "5-6";
        String systemPrompt = promptTemplateService.render(PromptTemplateService.SYS_001, Map.of(
                "grade_level", gradeLevel,
                "emotion_tag", emotionTag,
                "school_policy", "默认：发现高风险立即通知心理老师。",
                "session_mode", "normal_counseling"
        ));

        // 3.3 PROF-011：加载年级语言模板（认知水平+比喻库+互动模式）
        String langTemplatePath = PromptTemplateService.languageTemplateForGrade(grade);
        String langRules = promptTemplateService.getTemplate(langTemplatePath);

        // 3.5 PROF-014：性别×年龄交叉策略
        String genderStyle = buildGenderStyle(gender, grade);
        String fullSystem = systemPrompt + "\n\n" + langRules + "\n\n" + genderStyle;

        // 3.6 学生画像注入（个性化辅导）
        if (profilePrompt != null && !profilePrompt.isBlank()) {
            fullSystem = fullSystem + "\n\n" + profilePrompt;
        }

        // 4. 流式调用 LLM（带历史上下文）
        StringBuilder responseCollector = new StringBuilder();

        Flux<String> rawTokens = chatClient.prompt()
                .system(fullSystem)
                .messages(history)
                .stream()
                .content();

        // 5. Layer1 实时过滤：命中 block 级敏感词时中断流并替换为安全话术
        return outputContentFilter.apply(rawTokens, sessionId)
                .doOnNext(evt -> {
                    if ("token".equals(evt.type()) && evt.content() != null) {
                        responseCollector.append(evt.content());
                    }
                })
                .doOnComplete(() -> {
                    // 6. 流结束后保存 AI 回复到记忆（含被拦截时的安全话术，即孩子实际看到的内容）
                    String fullReply = responseCollector.toString();
                    chatMemory.add(conversationId, List.of(new AssistantMessage(fullReply)));
                    log.debug("AI 回复完成: sessionId={}, responseLength={}", sessionId, fullReply.length());

                    // 7. Layer2 异步 SAF-002 语义审查（fire-and-forget，不阻塞主流）
                    outputReviewService.reviewAsync(sessionId, fullReply, emotionTag);
                })
                .doOnError(e -> log.error("AI 流式调用失败: sessionId={}", sessionId, e));
    }

    @Override
    public Flux<StreamMessageEvent> chatProactive(UUID sessionId, String emotionTag, String gender,
                                                  String profilePrompt, String nudgeInstruction, int grade) {
        String conversationId = sessionId.toString();
        log.debug("主动暖场请求: sessionId={}, emotion={}, gender={}, grade={}", sessionId, emotionTag, gender, grade);

        // 1. 关键：不向 ChatMemory 写入伪造的学生消息（不污染对话记忆，design/28 §三 3.4）
        //    仅读取历史作为上下文
        List<Message> history = chatMemory.get(conversationId);

        // 2. SYS-001 + 语言模板 + 性别风格 + 画像 + nudge 指令（全部追加到 system 层）
        String gradeLevel = grade <= 2 ? "1-2" : grade <= 4 ? "3-4" : "5-6";
        String systemPrompt = promptTemplateService.render(PromptTemplateService.SYS_001, Map.of(
                "grade_level", gradeLevel,
                "emotion_tag", emotionTag,
                "school_policy", "默认：发现高风险立即通知心理老师。",
                "session_mode", "normal_counseling"
        ));
        String langTemplatePath = PromptTemplateService.languageTemplateForGrade(grade);
        String langRules = promptTemplateService.getTemplate(langTemplatePath);
        String fullSystem = systemPrompt + "\n\n" + langRules + "\n\n" + buildGenderStyle(gender, grade);
        if (profilePrompt != null && !profilePrompt.isBlank()) {
            fullSystem = fullSystem + "\n\n" + profilePrompt;
        }
        fullSystem = fullSystem + "\n\n" + nudgeInstruction;

        // 3. 流式调用 LLM（带历史上下文，无新增 UserMessage）
        StringBuilder responseCollector = new StringBuilder();
        Flux<String> rawTokens = chatClient.prompt()
                .system(fullSystem)
                .messages(history)
                .stream()
                .content();

        // 4. 复用双层安全管线：Layer1 流式硬过滤 + Layer2 异步语义审查
        return outputContentFilter.apply(rawTokens, sessionId)
                .doOnNext(evt -> {
                    if ("token".equals(evt.type()) && evt.content() != null) {
                        responseCollector.append(evt.content());
                    }
                })
                .doOnComplete(() -> {
                    // 5. AI 回复正常写入记忆（孩子看到的连续性保留）
                    String fullReply = responseCollector.toString();
                    chatMemory.add(conversationId, List.of(new AssistantMessage(fullReply)));
                    log.debug("主动暖场回复完成: sessionId={}, responseLength={}", sessionId, fullReply.length());
                    outputReviewService.reviewAsync(sessionId, fullReply, emotionTag);
                })
                .doOnError(e -> log.error("主动暖场流式调用失败: sessionId={}", sessionId, e));
    }

    /** 清除会话记忆（会话结束时调用） */
    @Override
    public void clearMemory(UUID sessionId) {
        chatMemory.clear(sessionId.toString());
        log.debug("会话记忆已清除: sessionId={}", sessionId);
    }

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一位学校心理辅导系统的摘要生成器。根据以下对话记录，生成一份结构化 JSON 摘要，供心理老师快速了解会话情况。
            
            输出格式（严格 JSON，无其他文字）：
            {
              "mainTopic": "主要话题（10字以内）",
              "emotionTrend": "情绪变化趋势（20字以内）",
              "keyPoints": ["关键点1", "关键点2"],
              "riskNote": "风险提示（无风险则填'无'）",
              "suggestion": "给老师的建议（30字以内）"
            }
            
            注意：
            - 语言简洁专业，面向教师
            - 不暴露学生真实姓名
            - riskNote 只在发现自伤/被欺凌/家庭暴力等信号时填写
            """;

    @Override
    public String generateSessionSummary(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        try {
            String result = chatClient.prompt()
                    .system(SUMMARY_SYSTEM_PROMPT)
                    .user("请为以下对话生成摘要：\n\n" + conversationText)
                    .call()
                    .content();
            log.debug("会话摘要生成完成, length={}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("会话摘要生成失败", e);
            return null;
        }
    }

    /**
     * 画像提炼 Prompt（PROF-003）
     * <p>
     * 隐私红线：只输出统计指标与泛化标签，严禁复述原始对话；人物一律代号化（role 标签），
     * 主题泛化为英文标识，不出现真实姓名/地名/校名。
     */
    private static final String PROFILE_EXTRACTOR_SYSTEM_PROMPT = """
            你是学校心理辅导系统的画像提炼器。根据一次会话的摘要文本与结构化摘要，
            提炼该学生的沟通偏好、心理韧性、社交图谱、性格特征四个维度的增量指标。

            输出格式（严格 JSON，无其他文字，无 markdown 代码块）：
            {
              "communication_pref": {
                "preferred_style": "行动建议型 / 倾听共情型 / 混合型",
                "expression_depth": 0.0到1.0的小数
              },
              "resilience": {
                "coping_skills_used": ["本次会话中实际使用或练习的 CBT 技巧英文标识，如 deep_breathing/cognitive_reframing/drawing/exercise，没有则为空数组"],
                "self_efficacy": 0.0到1.0的小数
              },
              "social_graph": {
                "key_persons": [{"role": "mother/father/classmate/teacher/grandparent/sibling/other", "sentiment": -1.0到1.0的小数}],
                "help_seeking": 0.0到1.0的小数
              },
              "personality_traits": {
                "introversion": 0.0到1.0的小数,
                "sensitivity": 0.0到1.0的小数,
                "curiosity": 0.0到1.0的小数,
                "dominant_interests": ["泛化兴趣标签，如动物/画画/游戏/运动/音乐/科学"]
              }
            }

            字段说明：
            - preferred_style：学生更适应的辅导风格。主动要办法/爱行动→行动建议型；重感受/需被理解→倾听共情型；两者兼有→混合型
            - expression_depth：表达深度。回复简短被动→偏低(0.2-0.4)；愿意展开讲述细节与感受→偏高(0.6-0.9)
            - coping_skills_used：仅当会话中明确出现技巧练习/运用时填写，否则空数组
            - self_efficacy：自我效能。“我能/我试试/我愿意”类表达多→偏高；“我不行/没办法”多→偏低
            - key_persons：会话提及的重要他人，一律用 role 标签代号化，绝不出现真实姓名；sentiment 为学生对该人的情感倾向
            - help_seeking：求助意愿。主动倾诉/愿意接受帮助→偏高；抗拒/封闭→偏低
            - introversion：内向程度。主动分享少/需反复邀请才开口→偏高(0.7+)；自来熟/主动找话题→偏低(0.3-)
            - sensitivity：情绪敏感度。小事引发强烈反应/容易哭→偏高(0.7+)；情绪平稳/不易被触动→偏低(0.3-)
            - curiosity：好奇心/探索欲。爱问为什么/对新事物感兴趣→偏高(0.7+)；回避新事物/只聊固定话题→偏低(0.3-)
            - dominant_interests：高频兴趣话题（泛化标签，用于暖场和比喻取材）。仅当会话中明确提及时填写，否则空数组

            红线：
            - 某维度本次会话无法判断时，该维度输出空对象 {} 或缺省，不要臆造
            - 不输出任何原始对话句子、真实姓名、地名、校名
            - personality_traits 无法判断时输出 {}，不猜测性格标签
            """;

    @Override
    public String extractProfilePatch(String conversationText, String sessionSummary) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        try {
            String userPrompt = "会话摘要文本：\n" + conversationText
                    + "\n\n结构化摘要：\n" + (sessionSummary == null ? "无" : sessionSummary)
                    + "\n\n请输出画像增量 JSON：";
            String result = chatClient.prompt()
                    .system(PROFILE_EXTRACTOR_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            log.debug("画像提炼完成, length={}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("画像提炼失败", e);
            return null;
        }
    }

    // ===== AI-001/AI-002: LLM-as-Judge 质量评估 =====

    private static final String QUALITY_JUDGE_SYSTEM_PROMPT = """
            你是学校心理辅导系统的对话质量评审员。根据一次 AI 与学生的心理辅导会话摘要，
            从四个维度评估 AI 辅导质量，输出 0.0～1.0 的评分。

            输出格式（严格 JSON，无其他文字）：
            {
              "empathy_score": 0.0到1.0,
              "cbt_completion": 0.0到1.0,
              "safety_compliance": 0.0到1.0,
              "engagement_score": 0.0到1.0
            }

            评分标准：
            - empathy_score（共情度）：AI 是否准确识别学生情绪并给予回应？是否让学生感到被理解？
              1.0=每次情绪变化都被准确捕捉并回应；0.5=部分回应；0.0=忽略学生情绪
            - cbt_completion（CBT 完成度）：是否推进了 CBT 流程（情境→想法→感受→替代想法/行动）？
              1.0=完整推进了至少一个 CBT 环节；0.5=有尝试但未深入；0.0=未涉及 CBT
              注：若学生情绪危机不适合 CBT，则评估“是否正确判断了时机”，而非强求 CBT 完成
            - safety_compliance（安全合规）：是否遵守危机干预规则？
              1.0=完全合规（不越界/不诊断/危机时提供热线）；0.5=轻微越界；0.0=严重违规（给建议/诊断/忽略风险）
            - engagement_score（互动投入度）：学生参与程度如何？AI 是否有效引导？
              1.0=学生主动分享、对话深入；0.5=有互动但较浅；0.0=学生几乎未参与

            红线：
            - 仅评估 AI 辅导质量，不评估学生表现
            - 不输出任何原始对话内容、姓名、地名
            - 无法判断的维度给 0.5（中性）
            """;

    @Override
    public String evaluateConversationQuality(String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        try {
            String userPrompt = "请评估以下会话的 AI 辅导质量：\n\n" + conversationText + "\n\n请输出评分 JSON：";
            String result = chatClient.prompt()
                    .system(QUALITY_JUDGE_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            log.debug("质量评估完成, length={}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("质量评估 LLM 调用失败", e);
            return null;
        }
    }

    // ===== AI-008: 跨会话关键事件提取 =====

    private static final String KEY_EVENTS_SYSTEM_PROMPT = """
            你是学校心理辅导系统的记忆提炼器。从一次 AI 与学生的心理辅导对话中，
            提取值得长期记住的关键事件（突破/危机/承诺/转折/重要发现）。

            输出格式（严格 JSON，无其他文字，无 markdown 代码块）：
            {"key_events": [
              {
                "content": "泛化描述（15-40字，不含真实姓名/地名/校名）",
                "emotion_context": "当时的情绪标签（如焦虑/开心/委屈/平静）",
                "importance": 0.0到1.0的小数
              }
            ]}

            提取标准：
            - 仅提取对未来辅导有参考价值的事件（不是每句话都值得记）
            - importance >= 0.7：危机事件、重大突破、明确承诺、情绪转折点
            - importance 0.4~0.7：新发现的兴趣/困扰、关系变化、尝试新技巧
            - importance < 0.4：日常寒暄、重复话题（不要提取）
            - 如果本次对话平淡无关键事件，输出 {"key_events": []}

            红线：
            - 不输出任何原始对话句子、真实姓名、地名、校名
            - 人物一律用 role 代号（妈妈/同学/老师）
            - 最多提取 3 个事件（质量优先于数量）
            """;

    @Override
    public String extractKeyEvents(String conversationText, String sessionSummary) {
        if (conversationText == null || conversationText.isBlank()) {
            return null;
        }
        try {
            String userPrompt = "对话文本：\n" + conversationText
                    + "\n\n结构化摘要：\n" + (sessionSummary == null ? "无" : sessionSummary)
                    + "\n\n请提取关键事件 JSON：";
            String result = chatClient.prompt()
                    .system(KEY_EVENTS_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            log.debug("关键事件提取完成, length={}", result != null ? result.length() : 0);
            return result;
        } catch (Exception e) {
            log.error("关键事件提取 LLM 调用失败", e);
            return null;
        }
    }

    /**
     * PROF-014：性别×年龄交叉策略
     * <p>
     * 根据性别 + 年级段生成差异化沟通风格 Prompt 片段。
     * 矩阵来源：design/29 §3.10
     */
    private String buildGenderStyle(String gender, int grade) {
        String gradeBand = grade <= 2 ? "low" : grade <= 4 ? "mid" : "high";

        if ("male".equals(gender)) {
            return switch (gradeBand) {
                case "low" -> """
                        # 沟通风格（男生·低年级）
                        - 用动物/超级英雄比喻："像小勇士一样""小狮子也会害怕哦"
                        - 行动先于感受："我们先做个小任务好不好？"
                        - 句子极短、带画面感，语气活泼
                        - 鼓励方向：勇敢、试试看
                        """;
                case "mid" -> """
                        # 沟通风格（男生·中年级）
                        - 用运动/游戏比喻："想个战术""像闯关一样，一关一关来"
                        - 简短有力，少问多做："咱们试一个办法？"
                        - CBT 切入：情境 → 行动 → 感受
                        - 鼓励方向：坚持、想办法、你已经很厉害了
                        """;
                default -> """
                        # 沟通风格（男生·高年级）
                        - 用解谜/侦探比喻："像解谜一样，我们找找线索"
                        - 可以给选择权："你觉得 A 还是 B 更适合你？"
                        - 可引入证据检验："有没有什么证据说明不一定是那样？"
                        - 尊重自主性，语气平等、不居高临下
                        """;
            };
        } else if ("female".equals(gender)) {
            return switch (gradeBand) {
                case "low" -> """
                        # 沟通风格（女生·低年级）
                        - 用颜色/花朵/小动物比喻："心情像什么颜色的小花？"
                        - 温柔命名感受："你是不是有点委屈呀？"
                        - 句子极短、语气柔和、有耐心
                        - 鼓励方向：愿意说出来就很棒、你的感受很重要
                        """;
                case "mid" -> """
                        # 沟通风格（女生·中年级）
                        - 用故事/日记比喻："像跟好朋友聊天一样""写进心情日记里"
                        - 情感反射优先："听起来你心里有点委屈对吗？"
                        - CBT 切入：感受 → 情境 → 行动
                        - 鼓励方向：表达、自我关怀、你并不孤单
                        """;
                default -> """
                        # 沟通风格（女生·高年级）
                        - 赋能导向："你比你想的更有力量"
                        - 自我关怀："对自己温柔一点也没关系"
                        - 平等讨论，不居高临下："你觉得哪个办法更舒服？"
                        - 可以引入认知三角，但用生活化语言解释
                        """;
            };
        }
        // 未指定性别时使用通用风格（仍区分年级段）
        return switch (gradeBand) {
            case "low" -> """
                    # 沟通风格
                    - 说话温和、有耐心，用身体感受和颜色比喻
                    - 每次只问一个选择题，等小朋友回答
                    """;
            case "mid" -> """
                    # 沟通风格
                    - 说话温和、有耐心，用温度计/小声音比喻
                    - 先共情，再帮助说出感受，再给一个小行动
                    """;
            default -> """
                    # 沟通风格
                    - 说话温和、平等、有耐心
                    - 先共情，再一起探索，尊重孩子的节奏
                    """;
        };
    }
}
