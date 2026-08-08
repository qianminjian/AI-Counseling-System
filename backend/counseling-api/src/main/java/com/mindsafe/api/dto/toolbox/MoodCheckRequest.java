package com.mindsafe.api.dto.toolbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 工具练习心情对比请求（TOOL-001 preMoodCheck/postMoodCheck，BA-14 请求体 DTO 化）
 * <p>
 * 替代原 {@code Map<String,Object>} 请求体：类型契约明确，前端传非数字/缺字段直接 400。
 * 情绪分范围（1-5）不做注解校验：越界值由 MoodCheckRecorder 判为 INVALID 等级（产品语义保留）。
 */
public record MoodCheckRequest(
        /** 工具 ID（见 ToolboxRegistry） */
        @NotBlank(message = "toolId 不能为空")
        String toolId,

        /** 练习前情绪分（1-5） */
        @NotNull(message = "preMood 不能为空")
        Integer preMood,

        /** 练习后情绪分（1-5） */
        @NotNull(message = "postMood 不能为空")
        Integer postMood
) {
}
