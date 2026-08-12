package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.TocChildProfile;

import java.time.Instant;
import java.util.UUID;

/**
 * toC 孩子档案 VO（F9：TocProfileController 响应，替代实体直接暴露）。
 * <p>
 * 字段语义与 {@link TocChildProfile} 一致（仅包装层变化，契约不变）。
 */
public record TocChildProfileVO(
        UUID profileId,
        UUID familyAccountId,
        String nickname,
        Integer age,
        String gender,
        String interests,
        Instant createdAt,
        Instant updatedAt
) {
    public static TocChildProfileVO from(TocChildProfile p) {
        return new TocChildProfileVO(p.getProfileId(), p.getFamilyAccountId(), p.getNickname(),
                p.getAge(), p.getGender(), p.getInterests(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
