package com.mindsafe.api.dto.chat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 冷场暖场请求（design/28 §六 6.1）
 * <p>
 * 前端沉默检测满足全部条件后调用 /nudge，上报沉默时长；
 * 后端冷场决策模型据此计算留白（空流）或暖场（SSE token 流）。
 */
public record NudgeRequest(
        /** 孩子已沉默的时长（秒），前端沉默检测计时器上报 */
        @Min(value = 1, message = "沉默时长必须大于0")
        @Max(value = 3600, message = "沉默时长异常")
        int silenceSeconds
) {}
