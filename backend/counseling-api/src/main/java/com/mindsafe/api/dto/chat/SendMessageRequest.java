package com.mindsafe.api.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 学生发送消息请求
 */
public record SendMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 500, message = "消息内容不能超过500字")
        String content
) {
}
