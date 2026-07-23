package com.mindsafe.common.dto.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话创建结果（服务层返回，API 层可直接使用或映射）
 */
public record SessionInfo(
        UUID sessionId,
        String greeting,
        Instant createdAt
) {
}
