package com.mindsafe.service.voice;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.ai.orchestrator.ProfileSignals;
import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import com.mindsafe.service.conversation.ConversationUtils;
import com.mindsafe.service.profile.StudentProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * 音色决策服务（TMATCH-001，design/48 §4.1/§5.1/§6.1）
 * <p>
 * 与 44 编排引擎并列的"输出侧决策"：把 tts-service 的 4 把声音升级为
 * "画像 × 情绪状态 → 音色 + prosody 基调"的自动匹配，前端不传 persona 也有合适默认。
 * <p>
 * 裁决优先级：安全场景锁定 > 手动偏好 > 冷启动画像默认；prosody 随情绪+年龄调整。
 * 纯规则映射零 LLM；任何查询失败回落通用安全默认（TTS 决策永不阻塞播放）。
 */
@Service
public class VoicePersonaResolver {

    private static final Logger log = LoggerFactory.getLogger(VoicePersonaResolver.class);

    /** tts-service VOICE_PERSONAS 同源集合（design/56 7 音色矩阵） */
    private static final Set<String> VALID_PERSONAS = Set.of(
            "xiaoxing", "bobo", "yueliang", "xiaotaiyang", "dashu", "doudou", "qiqiu");

    /** 通用安全默认（design/48 §5.1：中年级+中性/未知 → 温暖大姐姐） */
    private static final String FALLBACK_PERSONA = "xiaoxing";

    /** 外向判定阈值：introversion 低于该值视为活泼外向画像 → qiqiu */
    private static final double EXTROVERT_THRESHOLD = 0.35;

    /** ACTIVATED 情绪集（design/48 §6.1：安抚基调 = 更慢、音高更低、停顿更多） */
    private static final Set<String> ACTIVATED_EMOTIONS = Set.of("sad", "angry", "fearful", "nervous");

    private final UserMapper userMapper;
    private final StudentProfileService profileService;

    public VoicePersonaResolver(UserMapper userMapper, StudentProfileService profileService) {
        this.userMapper = userMapper;
        this.profileService = profileService;
    }

    /**
     * 解析本次合成的音色渲染档案。
     *
     * @param tenantId         租户（可为 null → 跳过画像查询走通用默认）
     * @param userId           学生用户（可为 null）
     * @param requestedPersona 前端显式指定的 persona（学生手动选择，最高优先；null/非法 → 自动匹配）
     * @param emotion          当前情绪标签（happy/sad/angry/fearful/nervous/neutral）
     * @param scene            场景（chat/safety/crisis），安全/危机强制稳定基调锁定
     * @param dialect          方言代码（可为 null，透传给 tts-service，design/56 §三）
     */
    public VoiceRenderProfile resolve(UUID tenantId, UUID userId,
                                      String requestedPersona, String emotion, String scene,
                                      String dialect) {
        User user = lookupUser(tenantId, userId);
        int grade = user != null ? ConversationUtils.parseGradeCode(user.getGradeCode()) : 4;

        // 1. persona 裁决：手动 > 画像冷启动
        String persona;
        String source;
        if (requestedPersona != null && VALID_PERSONAS.contains(requestedPersona)) {
            persona = requestedPersona;
            source = "manual";
        } else {
            PersonaChoice choice = coldStartPersona(tenantId, userId, user, grade);
            persona = choice.persona;
            source = choice.source;
        }

        // 2. 安全/危机场景：锁定稳定基调 + 中性 instruct（红线：情绪 instruct 不得干扰合规话术）
        if ("safety".equals(scene) || "crisis".equals(scene)) {
            return new VoiceRenderProfile(persona, "neutral", 0.95, 0.88, 2, true, source, dialect);
        }

        // 3. 情绪 → prosody 基调（design/48 §6.1）
        double pitchScale;
        double speed;
        int pauseStyle;
        if (emotion != null && ACTIVATED_EMOTIONS.contains(emotion)) {
            // 情绪激活态：安抚基调
            pitchScale = 0.9;
            speed = 0.9;
            pauseStyle = 2;
        } else if ("happy".equals(emotion)) {
            pitchScale = 1.05;
            speed = 1.0;
            pauseStyle = 0;
        } else {
            pitchScale = 1.0;
            speed = 1.0;
            pauseStyle = 1;
        }

        // 4. 分龄基调（design/48 §5.2：低龄更慢、停顿更多）
        if (grade <= 2) {
            speed *= 0.92;
            pauseStyle = Math.max(pauseStyle, 2);
        }

        String instruct = emotion != null && !emotion.isBlank() ? emotion : "neutral";
        return new VoiceRenderProfile(persona, instruct, pitchScale, speed, pauseStyle, false, source, dialect);
    }

    private record PersonaChoice(String persona, String source) {
    }

    /**
     * 冷启动默认匹配（design/48 §5.1）：
     * 低龄(≤2) → yueliang；男生+中高年级 → xiaotaiyang；活泼外向画像 → qiqiu；否则 xiaoxing。
     * 性别取自 User.gender（认同/偏好语义，始终可被手动覆盖）。
     */
    private PersonaChoice coldStartPersona(UUID tenantId, UUID userId, User user, int grade) {
        if (user == null) {
            return new PersonaChoice(FALLBACK_PERSONA, "default");
        }
        try {
            if (grade <= 2) {
                return new PersonaChoice("yueliang", "default");
            }
            if ("male".equals(user.getGender())) {
                return new PersonaChoice("xiaotaiyang", "default");
            }
            // 画像微调：高置信外向 → 活泼小伙伴（置信门控与编排同源，宁可不用不可乱用）
            ProfileSignals signals = profileService.getProfileSignals(tenantId, userId);
            if (signals != null && signals.introversionUsable()
                    && signals.introversion() < EXTROVERT_THRESHOLD) {
                return new PersonaChoice("qiqiu", "profile");
            }
            return new PersonaChoice(FALLBACK_PERSONA, "default");
        } catch (Exception e) {
            log.warn("音色冷启动匹配失败（回落默认，不阻塞播放）: userId={}, error={}", userId, e.getMessage());
            return new PersonaChoice(FALLBACK_PERSONA, "default");
        }
    }

    /** 查用户供冷启动匹配与分龄基调用；失败/缺失 → null（回落通用默认） */
    private User lookupUser(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            return null;
        }
        try {
            return userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, tenantId)
                    .eq(User::getUserId, userId));
        } catch (Exception e) {
            log.warn("音色决策查用户失败（回落默认，不阻塞播放）: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }
}
