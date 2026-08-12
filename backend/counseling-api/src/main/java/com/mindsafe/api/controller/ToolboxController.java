package com.mindsafe.api.controller;

import com.mindsafe.api.dto.toolbox.MoodCheckRequest;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.User;
import com.mindsafe.service.auth.AuthUserService;
import com.mindsafe.service.conversation.ConversationUtils;
import com.mindsafe.service.toolbox.MoodCheckRecorder;
import com.mindsafe.service.toolbox.MoodCheckService;
import com.mindsafe.service.toolbox.ToolboxRegistry;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import jakarta.validation.Valid;
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

    private final ToolboxRegistry toolboxRegistry;
    private final MoodCheckService moodCheckService;
    private final AuthUserService authUserService;

    public ToolboxController(ToolboxRegistry toolboxRegistry,
                             MoodCheckService moodCheckService,
                             AuthUserService authUserService) {
        this.toolboxRegistry = toolboxRegistry;
        this.moodCheckService = moodCheckService;
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
            throw new BizException(ErrorCode.PARAM_INVALID, "无效的工具分类: " + category);
        }
    }

    /**
     * 记录工具练习前后情绪对比（TOOL-001 preMoodCheck/postMoodCheck）
     * <p>
     * BA-03（DOC-074）：练习完成落库 relaxation_sessions（exerciseType=toolId，
     * 支撑工具徽章数据源）；情绪对比（preMood/postMood/delta）为 S3 级画像数据，
     * 消费方（frozen/39 画像与实验）冻结，暂不持久化。
     * BA-14：编排下沉 MoodCheckService，Controller 薄壳（请求体 DTO + 错误统一走全局异常处理）。
     */
    @PostMapping("/mood-check")
    public ApiResponse<Map<String, Object>> recordMoodCheck(
            @Valid @RequestBody MoodCheckRequest request, Authentication auth) {
        TenantContext ctx = extractContext(auth);

        MoodCheckService.MoodCheckResult result = moodCheckService.record(
                ctx.tenantId(), ctx.userId(), request.toolId(), request.preMood(), request.postMood());

        MoodCheckRecorder.MoodEffect effect = result.effect();
        return ApiResponse.ok(Map.of(
                "toolId", effect.toolId(),
                "preMood", effect.preMood(),
                "postMood", effect.postMood(),
                "delta", effect.delta(),
                "level", effect.level().name(),
                "needsAttention", result.needsAttention()
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
