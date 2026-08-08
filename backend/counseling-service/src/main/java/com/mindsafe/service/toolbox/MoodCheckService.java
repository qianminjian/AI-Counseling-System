package com.mindsafe.service.toolbox;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.service.relaxation.RelaxationService;
import com.mindsafe.service.toolbox.MoodCheckRecorder.MoodEffect;
import com.mindsafe.service.toolbox.ToolboxRegistry.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 工具练习心情对比编排服务（TOOL-001，BA-14 从 ToolboxController 下沉）
 * <p>
 * 编排链：参数校验 → 工具存在性验证 → 效果计算 → 练习完成落库（relaxation_sessions）→ 恶化告警。
 * Controller 恢复薄壳；本服务成为可测单元（原编排零覆盖）。
 */
@Component
public class MoodCheckService {

    private static final Logger log = LoggerFactory.getLogger(MoodCheckService.class);

    private final ToolboxRegistry toolboxRegistry;
    private final MoodCheckRecorder moodCheckRecorder;
    private final RelaxationService relaxationService;

    public MoodCheckService(ToolboxRegistry toolboxRegistry,
                            MoodCheckRecorder moodCheckRecorder,
                            RelaxationService relaxationService) {
        this.toolboxRegistry = toolboxRegistry;
        this.moodCheckRecorder = moodCheckRecorder;
        this.relaxationService = relaxationService;
    }

    /** 记录结果（effect + 是否需关注） */
    public record MoodCheckResult(MoodEffect effect, boolean needsAttention) {
    }

    /**
     * 记录一次工具练习心情对比。
     *
     * @param tenantId 租户 ID
     * @param userId   学生用户 ID
     * @param toolId   工具 ID（null → 400）
     * @param preMood  练习前情绪分（null → 400）
     * @param postMood 练习后情绪分（null → 400）
     * @return 效果记录
     * @throws BizException 缺参数（PARAM_INVALID）/ 未知工具（PARAM_INVALID）
     */
    public MoodCheckResult record(UUID tenantId, UUID userId, String toolId, Integer preMood, Integer postMood) {
        if (toolId == null || preMood == null || postMood == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少必要参数: toolId, preMood, postMood");
        }

        Optional<ToolDefinition> toolOpt = toolboxRegistry.getById(toolId);
        if (toolOpt.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知工具: " + toolId);
        }

        MoodEffect effect = moodCheckRecorder.record(toolId, preMood, postMood);

        // 练习完成落库（徽章数据源：exercise_type=toolId；时长取工具定义真实值，review 修正 0 秒失真记录）
        relaxationService.recordSession(tenantId, userId, toolId, toolOpt.get().durationSec(), true);

        log.info("工具练习情绪记录: userId={}, toolId={}, pre={}, post={}, delta={}, level={}",
                userId, toolId, preMood, postMood, effect.delta(), effect.level());

        boolean needsAttention = moodCheckRecorder.needsAttention(effect);
        // 恶化需特别关注（可触发教师关注信号，后续接 MEM-103）
        if (needsAttention) {
            log.warn("工具练习后情绪恶化，需关注: userId={}, toolId={}", userId, toolId);
        }

        return new MoodCheckResult(effect, needsAttention);
    }
}
