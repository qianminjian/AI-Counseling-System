package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.School;

import java.time.Instant;
import java.util.UUID;

/**
 * 学校 VO（F9：平台学校列表响应，替代实体直接暴露）。
 */
public record SchoolVO(
        UUID schoolId,
        UUID tenantId,
        String schoolCode,
        String schoolName,
        String eduStage,
        String province,
        String city,
        String district,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public static SchoolVO from(School s) {
        return new SchoolVO(s.getSchoolId(), s.getTenantId(), s.getSchoolCode(), s.getSchoolName(),
                s.getEduStage(), s.getProvince(), s.getCity(), s.getDistrict(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getDeletedAt());
    }
}
