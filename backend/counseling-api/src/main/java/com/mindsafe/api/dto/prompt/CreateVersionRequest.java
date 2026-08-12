package com.mindsafe.api.dto.prompt;

import java.util.UUID;

/**
 * Prompt 版本创建请求（F11：createVersion 请求体类型化，替代 Map&lt;String, String&gt;；F12：请求 DTO 落 api/dto 子包）。
 * <p>
 * 字段语义与原 Map 约定完全一致：abGroup 缺省为 control；
 * tenantId 为 null 时视为平台级版本（与原 containsKey 语义等价，且 null 值不再触发 NPE）。
 */
public record CreateVersionRequest(
        String templateKey,
        String content,
        String description,
        String abGroup,
        UUID tenantId
) {
    public CreateVersionRequest {
        if (abGroup == null) {
            abGroup = "control";
        }
    }
}
