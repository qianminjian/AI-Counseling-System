package com.mindsafe.service.conversation;

import com.mindsafe.ai.orchestrator.StrategyProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 对话上下文主 Agent（CTX-Agent）
 * <p>
 * 职责：将分散的上下文信号（身份/情绪/记忆/画像/主题）组装为结构化 Context Brief，
 * 注入 System Prompt 的 Layer 3（上下文层），让 AI 每轮都拥有完整认知：
 * <ul>
 *   <li>我在和谁说话（身份简报）</li>
 *   <li>孩子现在怎么样（情绪旅程）</li>
 *   <li>之前发生了什么（会话进展 + 历史记忆）</li>
 *   <li>当前在聊什么（主题线索）</li>
 * </ul>
 * <p>
 * 纯字符串组装，零 LLM 调用，零 IO（所有数据由调用方预加载）。
 */
@Service
public class ConversationContextAgent {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextAgent.class);

    /** 情绪标签中文映射（语音 SER 规范集 → 儿童友好表述） */
    private static final Map<String, String> EMOTION_LABELS = Map.ofEntries(
            Map.entry("happy", "开心"),
            Map.entry("sad", "难过"),
            Map.entry("angry", "生气"),
            Map.entry("fearful", "害怕"),
            Map.entry("anxious", "紧张"),
            Map.entry("calm", "平静"),
            Map.entry("withdrawn", "沉默"),
            Map.entry("disgusted", "烦躁"),
            Map.entry("surprised", "惊讶"),
            Map.entry("neutral", "平静")
    );

    /**
     * 构建完整上下文简报（每轮调用，注入 System Prompt Layer 3）。
     *
     * @param session        当前会话状态
     * @param profilePrompt  学生画像 Prompt（由 StudentProfileService 构建，可为 null）
     * @param memoryPrompt   跨会话长期记忆 Prompt（由 LongTermMemoryService 构建，可为 null）
     * @param alliancePrompt 治疗联盟连续性提示（由 AllianceEnhancer 构建，可为 null）
     * @param totalSessions  该学生历史会话总次数（用于"第 N 次对话"）
     * @return 结构化 Context Brief 文本
     */
    public String buildContextBrief(SessionState session, String profilePrompt,
                                    String memoryPrompt, String alliancePrompt,
                                    int totalSessions) {
        StringBuilder brief = new StringBuilder();

        // Section 1: 身份简报
        brief.append(buildIdentityBrief(session, totalSessions));

        // Section 2: 情绪旅程
        brief.append(buildEmotionJourney(session));

        // Section 3: 会话上下文（滚动摘要 + 主题线索 + 历史记忆 + 联盟续接）
        brief.append(buildSessionContext(session, memoryPrompt, alliancePrompt));

        // Section 4: 学生画像（个性化参数，已由 ProfileService 格式化）
        if (profilePrompt != null && !profilePrompt.isBlank()) {
            brief.append("\n").append(profilePrompt).append("\n");
        }

        return brief.toString();
    }

    // ===== Section 1: 身份简报 =====

    private String buildIdentityBrief(SessionState session, int totalSessions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 你正在和谁说话\n\n");

        // 昵称（核心：让 AI 知道孩子叫什么）
        String name = session.getPseudonym();
        if (name != null && !name.isBlank()) {
            sb.append("- 昵称：").append(name).append("（请自然地称呼").append(name).append("）\n");
        } else {
            sb.append("- 昵称：未知（这是新同学，可以温和地询问怎么称呼）\n");
        }

        // 会话级个人信息（对话中收集，每轮注入，确保 AI 不遗忘）
        Map<String, String> personalInfo = session.getPersonalInfo();
        if (personalInfo != null && !personalInfo.isEmpty()) {
            sb.append("- 孩子告诉你的个人信息（必须记住，不可遗忘）：\n");
            String realName = personalInfo.get("realName");
            if (realName != null) {
                sb.append("  - 真实名字：").append(realName).append("（优先用这个名字称呼）\n");
            }
            String age = personalInfo.get("age");
            if (age != null) {
                sb.append("  - 年龄：").append(age).append("\n");
            }
            String grade = personalInfo.get("grade");
            if (grade != null) {
                sb.append("  - 年级：").append(grade).append("\n");
            }
            String clazz = personalInfo.get("class");
            if (clazz != null) {
                sb.append("  - 班级：").append(clazz).append("\n");
            }
        }

        // 年级 + 年龄估算
        int grade = session.getGrade();
        int approxAge = grade + 5;
        sb.append("- 年级：").append(grade).append(" 年级（约 ").append(approxAge).append("-").append(approxAge + 1).append(" 岁）\n");

        // 性别
        String gender = session.getGender();
        if (gender != null && !gender.isBlank()) {
            String genderLabel = "male".equals(gender) ? "男" : "female".equals(gender) ? "女" : "未指定";
            sb.append("- 性别：").append(genderLabel).append("\n");
        }

        // 对话次数（让 AI 知道是初次还是老朋友）
        if (totalSessions > 1) {
            sb.append("- 这是你们的第 ").append(totalSessions).append(" 次对话（你们是老朋友了）\n");
        } else {
            sb.append("- 这是你们的第一次对话（初次见面，先建立信任）\n");
        }

        // 进入心情
        String emotionTag = session.getEmotionTag();
        if (emotionTag != null && !emotionTag.isBlank()) {
            sb.append("- 进入心情：").append(toChinese(emotionTag)).append("\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    // ===== Section 2: 情绪旅程 =====

    private String buildEmotionJourney(SessionState session) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 孩子的情绪变化\n\n");

        // 进入心情
        String entryMood = session.getEmotionTag();
        sb.append("- 进入时：").append(entryMood != null ? toChinese(entryMood) : "未选择").append("\n");

        // 语音情绪轨迹（最近 5 条）
        List<SessionState.EmotionRecord> history = session.getEmotionHistory();
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 5);
            StringBuilder trail = new StringBuilder();
            for (int i = start; i < history.size(); i++) {
                if (i > start) trail.append(" → ");
                trail.append(toChinese(history.get(i).emotion()));
            }
            sb.append("- 对话中情绪轨迹：").append(trail).append("\n");

            // 趋势判断
            String trend = assessTrend(history);
            if (trend != null) {
                sb.append("- 趋势：").append(trend).append("\n");
            }
        }

        // 状态机当前状态
        StrategyProfile.EmotionState state = session.getEmotionState();
        String stateLabel = switch (state) {
            case STABLE -> "情绪趋稳，可以正常推进对话";
            case ACTIVATED -> "⚠️ 情绪激活中，优先稳定情绪，不追问、不推进 CBT";
            case CRISIS -> "🚨 危机状态，安全响应模式，仅做陪伴和稳定";
        };
        sb.append("- 当前状态：").append(stateLabel).append("\n");

        // 缓解计数（连续积极回应）
        if (session.getReliefCount() >= 2) {
            sb.append("- 注意：连续 ").append(session.getReliefCount()).append(" 轮积极回应，可以适度推进话题\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    // ===== Section 3: 会话上下文 =====

    private String buildSessionContext(SessionState session, String memoryPrompt, String alliancePrompt) {
        StringBuilder sb = new StringBuilder();

        // 3a: 滚动摘要（本次对话进展）
        String summary = session.getSessionSummary();
        List<SessionState.TopicHint> topics = session.getTopicHints();
        if ((summary != null && !summary.isBlank()) || (topics != null && !topics.isEmpty())) {
            sb.append("# 本次对话进展\n\n");
            if (summary != null && !summary.isBlank()) {
                sb.append("[滚动摘要] ").append(summary).append("\n");
                sb.append("（注：最近几轮对话原文已在对话记忆中，以上摘要覆盖更早的内容）\n\n");
            }
            if (topics != null && !topics.isEmpty()) {
                sb.append("[主题线索]\n");
                for (SessionState.TopicHint hint : topics) {
                    sb.append("- ").append(hint.topic());
                    sb.append("（第 ").append(hint.firstTurn()).append(" 轮提起");
                    if (hint.count() > 1) {
                        sb.append("，出现 ").append(hint.count()).append(" 次");
                    }
                    sb.append("）\n");
                }
                sb.append("\n");
            }
        }

        // 3b: 历史记忆（跨会话，按当前主题相关性重排序）
        if (memoryPrompt != null && !memoryPrompt.isBlank()) {
            sb.append(reorderMemoryByRelevance(memoryPrompt, topics)).append("\n\n");
        }

        // 3c: 联盟续接（上次聊了什么）
        if (alliancePrompt != null && !alliancePrompt.isBlank()) {
            sb.append(alliancePrompt).append("\n\n");
        }

        return sb.toString();
    }

    // ===== 辅助方法 =====

    /** 情绪标签 → 中文（支持中英文输入） */
    private String toChinese(String emotion) {
        if (emotion == null) return "未知";
        String mapped = EMOTION_LABELS.get(emotion.toLowerCase());
        return mapped != null ? mapped : emotion;
    }

    /** 评估情绪趋势（对比前半段和后半段） */
    private String assessTrend(List<SessionState.EmotionRecord> history) {
        if (history.size() < 3) return null;

        int mid = history.size() / 2;
        long earlyNegative = history.subList(0, mid).stream()
                .filter(r -> isNegative(r.emotion())).count();
        long lateNegative = history.subList(mid, history.size()).stream()
                .filter(r -> isNegative(r.emotion())).count();

        double earlyRatio = (double) earlyNegative / mid;
        double lateRatio = (double) lateNegative / (history.size() - mid);

        if (earlyRatio > 0.5 && lateRatio < 0.3) return "正在好转（从负面转向积极）";
        if (earlyRatio < 0.3 && lateRatio > 0.5) return "⚠️ 正在恶化（从积极转向负面）";
        if (lateRatio > 0.7) return "持续低落，需要更多关注";
        if (lateRatio == 0) return "持续积极，状态良好";
        return null;
    }

    private boolean isNegative(String emotion) {
        if (emotion == null) return false;
        String lower = emotion.toLowerCase();
        return "sad".equals(lower) || "angry".equals(lower) || "fearful".equals(lower)
                || "anxious".equals(lower) || "withdrawn".equals(lower) || "disgusted".equals(lower);
    }

    /**
     * Enhancement 1: 按当前主题相关性对记忆条目重排序（零 LLM，纯关键词匹配）。
     * 与当前 topicHints 相关的记忆排在前面，不相关的保持原序但不删除。
     */
    private String reorderMemoryByRelevance(String memoryPrompt, List<SessionState.TopicHint> topics) {
        if (topics == null || topics.isEmpty()) return memoryPrompt;

        String[] lines = memoryPrompt.split("\n");
        if (lines.length <= 2) return memoryPrompt; // 太短不值得排序

        // 提取主题关键词集
        java.util.Set<String> topicKeywords = new java.util.HashSet<>();
        for (SessionState.TopicHint hint : topics) {
            topicKeywords.add(hint.topic());
            // 拆解复合主题（如"和妈妈的关系" → "妈妈"）
            String t = hint.topic().replace("和", "").replace("的关系", "").replace("压力", "").replace("倾向", "").replace("感", "");
            if (t.length() >= 2) topicKeywords.add(t);
        }

        // 分为相关/不相关两组
        java.util.List<String> relevant = new java.util.ArrayList<>();
        java.util.List<String> others = new java.util.ArrayList<>();
        for (String line : lines) {
            boolean matched = false;
            for (String kw : topicKeywords) {
                if (line.contains(kw)) { matched = true; break; }
            }
            if (matched) relevant.add(line);
            else others.add(line);
        }

        // 相关条目排前，其余保持原序
        if (relevant.isEmpty()) return memoryPrompt;
        StringBuilder reordered = new StringBuilder();
        for (String l : relevant) reordered.append(l).append("\n");
        for (String l : others) reordered.append(l).append("\n");
        return reordered.toString().trim();
    }
}
