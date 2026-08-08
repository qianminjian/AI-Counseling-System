package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.conversation.ConversationUtils;
import com.mindsafe.service.relaxation.RelaxationService;
import com.mindsafe.service.toolbox.MoodCheckRecorder;
import com.mindsafe.service.toolbox.ToolboxRegistry;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 心理工具箱 API（TOOL-001/002，design/36）
 * <p>
 * 接线孤儿组件：{@link ToolboxRegistry}（工具注册表）+ {@link MoodCheckRecorder}（前后心情记录）。
 * <ul>
 *   <li>GET /toolbox — 按学生年级过滤可用工具</li>
 *   <li>GET /toolbox/sos — SOS 可用工具（接地+呼吸，离线可打开）</li>
 *   <li>POST /toolbox/mood-check — 记录练习前后情绪对比（BA-03 起落库练习完成）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/toolbox")
public class ToolboxController {

    private static final Logger log = LoggerFactory.getLogger(ToolboxController.class);

    private final ToolboxRegistry toolboxRegistry;
    private final MoodCheckRecorder moodCheckRecorder;
    private final RelaxationService relaxationService;
    private final AuthUserService authUserService;

    public ToolboxController(ToolboxRegistry toolboxRegistry,
                             MoodCheckRecorder moodCheckRecorder,
                             RelaxationService relaxationService,
                             AuthUserService authUserService) {
        this.toolboxRegistry = toolboxRegistry;
        this.moodCheckRecorder = moodCheckRecorder;
        this.relaxationService = relaxationService;
        this.authUserService = authUserService;
    }

    /**
     * 获取当前学生可用工具列表（按年级过滤）
     */
    @GetMapping
    public ApiResponse<List<ToolDefinition>> listTools(Authentication auth) {
        int grade = resolveGrade(auth);
        List<ToolDefinition> tools = toolboxRegistry.listForGrade(grade);
        return ApiResponse.ok(tools);
    }

    /**
     * 获取 SOS 可用工具（接地+呼吸，断网可打开、热线可拨号）
     */
    @GetMapping("/sos")
    public ApiResponse<List<ToolDefinition>> sosTools() {
        return ApiResponse.ok(toolboxRegistry.listSosTools());
    }

    /**
     * 按分类获取工具
     */
    @GetMapping("/category/{category}")
    public ApiResponse<List<ToolDefinition>> byCategory(@PathVariable String category) {
        try {
            ToolboxRegistry.ToolCategory cat = ToolboxRegistry.ToolCategory.valueOf(category.toUpperCase());
            return ApiResponse.ok(toolboxRegistry.listByCategory(cat));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "无效的工具分类: " + category);
        }
    }

    /**
     * 记录工具练习前后情绪对比（TOOL-001 preMoodCheck/postMoodCheck）
     * <p>
     * BA-03（DOC-074）：练习完成落库 relaxation_sessions（exerciseType=toolId，
     * 支撑工具徽章数据源）；情绪对比（preMood/postMood/delta）为 S3 级画像数据，
     * 消费方（frozen/39 画像与实验）冻结，暂不持久化。
     * 恶化（WORSENED）需特别关注，返回 needsAttention=true。
     */
    @PostMapping("/mood-check")
    public ApiResponse<Map<String, Object>> recordMoodCheck(
            @RequestBody Map<String, Object> request, Authentication auth) {
        TenantContext ctx = extractContext(auth);

        String toolId = (String) request.get("toolId");
        Integer preMood = (Integer) request.get("preMood");
        Integer postMood = (Integer) request.get("postMood");

        if (toolId == null || preMood == null || postMood == null) {
            return ApiResponse.error(400, "缺少必要参数: toolId, preMood, postMood");
        }

        // 验证工具存在
        Optional<ToolDefinition> toolOpt = toolboxRegistry.getById(toolId);
        if (toolOpt.isEmpty()) {
            return ApiResponse.error(400, "未知工具: " + toolId);
        }

        MoodCheckRecorder.MoodEffect effect = moodCheckRecorder.record(toolId, preMood, postMood);

        // 练习完成落库（徽章数据源：exercise_type=toolId；时长取工具定义真实值，review 修正 0 秒失真记录）
        relaxationService.recordSession(ctx.tenantId(), ctx.userId(), toolId, toolOpt.get().durationSec(), true);

        log.info("工具练习情绪记录: userId={}, toolId={}, pre={}, post={}, delta={}, level={}",
                ctx.userId(), toolId, preMood, postMood, effect.delta(), effect.level());

        // 恶化需特别关注（可触发教师关注信号，后续接 MEM-103）
        if (moodCheckRecorder.needsAttention(effect)) {
            log.warn("工具练习后情绪恶化，需关注: userId={}, toolId={}", ctx.userId(), toolId);
        }

        return ApiResponse.ok(Map.of(
                "toolId", effect.toolId(),
                "preMood", effect.preMood(),
                "postMood", effect.postMood(),
                "delta", effect.delta(),
                "level", effect.level().name(),
                "needsAttention", moodCheckRecorder.needsAttention(effect)
        ));
    }

    /** 从认证信息解析学生年级（1-6，默认 4；BA-07：用户查询收敛 AuthUserService） */
    private int resolveGrade(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof UUID userId)) return 4;
        User user = authUserService.findById(userId);
        if (user == null) return 4;
        return ConversationUtils.parseGradeCode(user.getGradeCode());
    }

    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }
}
