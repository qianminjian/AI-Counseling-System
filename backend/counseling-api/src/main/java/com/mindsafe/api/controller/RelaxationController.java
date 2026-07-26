package com.mindsafe.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.dto.ApiResponse;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.RelaxationSession;
import com.mindsafe.domain.mapper.RelaxationSessionMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 放松练习 API（对齐 design/16 §3）
 * <p>
 * 功能：练习列表 / 记录练习完成
 */
@RestController
@RequestMapping("/api/v1/relaxation")
public class RelaxationController {

    private final RelaxationSessionMapper relaxationSessionMapper;

    /** 内置放松练习列表 */
    private static final List<ExerciseVO> EXERCISES = List.of(
            new ExerciseVO("breathing_323", "3-2-3 呼吸法",
                    "吸气 3 秒，屏住 2 秒，呼气 3 秒。重复 5 次，让身体慢慢放松下来。",
                    60, "breathing"),
            new ExerciseVO("breathing_478", "4-7-8 深呼吸",
                    "吸气 4 秒，屏住 7 秒，呼气 8 秒。适合睡前或紧张时使用。",
                    90, "breathing"),
            new ExerciseVO("body_scan", "身体扫描放松",
                    "从头顶到脚尖，逐步感受身体每个部位，释放紧张。",
                    120, "mindfulness"),
            new ExerciseVO("safe_place", "安全空间想象",
                    "闭上眼睛，想象一个让你感到安全和快乐的地方，感受那里的声音、颜色和温度。",
                    90, "visualization"),
            new ExerciseVO("butterfly_hug", "蝴蝶拥抱",
                    "双手交叉放在胸前，像蝴蝶翅膀一样交替轻拍肩膀，同时深呼吸。",
                    60, "somatic")
    );

    public RelaxationController(RelaxationSessionMapper relaxationSessionMapper) {
        this.relaxationSessionMapper = relaxationSessionMapper;
    }

    /** 获取放松练习列表 */
    @GetMapping("/exercises")
    public ApiResponse<List<ExerciseVO>> getExercises() {
        return ApiResponse.ok(EXERCISES);
    }

    /** 记录练习完成 */
    @PostMapping("/sessions")
    public ApiResponse<RelaxationSession> recordSession(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        TenantContext ctx = extractContext(auth);

        String exerciseType = (String) body.getOrDefault("exerciseType", "breathing_323");
        int duration = body.containsKey("durationSeconds")
                ? ((Number) body.get("durationSeconds")).intValue() : 60;
        boolean completed = body.containsKey("completed")
                ? (Boolean) body.get("completed") : true;

        RelaxationSession session = RelaxationSession.create(
                ctx.tenantId(), ctx.userId(), exerciseType, duration, completed);
        relaxationSessionMapper.insert(session);

        return ApiResponse.ok(session);
    }

    /** 今日练习计数 */
    @GetMapping("/sessions/today")
    public ApiResponse<Map<String, Object>> getTodayCount(Authentication auth) {
        TenantContext ctx = extractContext(auth);
        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();

        Long count = relaxationSessionMapper.selectCount(
                new LambdaQueryWrapper<RelaxationSession>()
                        .eq(RelaxationSession::getTenantId, ctx.tenantId())
                        .eq(RelaxationSession::getStudentUserId, ctx.userId())
                        .eq(RelaxationSession::getCompleted, true)
                        .ge(RelaxationSession::getCreatedAt, todayStart)
        );
        return ApiResponse.ok(Map.of("count", count));
    }

    private TenantContext extractContext(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof TenantContext ctx)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ctx;
    }

    /** 练习 VO */
    public record ExerciseVO(
            String id, String name, String description,
            int durationSeconds, String category
    ) {}
}
