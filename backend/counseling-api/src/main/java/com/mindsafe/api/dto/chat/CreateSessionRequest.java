package com.mindsafe.api.dto.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建对话会话请求
 */
public record CreateSessionRequest(
        @NotBlank(message = "情绪标签不能为空")
        String emotionTag,
        String channel
) {
    public CreateSessionRequest {
        if (channel == null || channel.isBlank()) {
            channel = "web";
        }
    }
}
