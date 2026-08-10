package com.mindsafe.service.toc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.domain.mapper.TocChildProfileMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * toC 家庭档案服务（doing/85 TOC-002，toC-AC-2）
 * <p>
 * 一账号多孩档案 CRUD，数据按 familyAccountId 严格隔离（查询/修改/删除均
 * 带归属校验，跨账号访问拒绝）。
 */
@Service
public class TocFamilyService {

    private final TocChildProfileMapper profileMapper;

    public TocFamilyService(TocChildProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    /** 档案列表（按 familyAccountId 隔离）。 */
    public List<TocChildProfile> listProfiles(UUID familyAccountId) {
        return profileMapper.selectList(
                new LambdaQueryWrapper<TocChildProfile>()
                        .eq(TocChildProfile::getFamilyAccountId, familyAccountId)
                        .orderByDesc(TocChildProfile::getCreatedAt));
    }

    /** 创建档案。 */
    public TocChildProfile createProfile(UUID familyAccountId, Map<String, Object> body) {
        String nickname = stringValue(body.get("nickname"));
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("昵称必填");
        }
        TocChildProfile profile = new TocChildProfile();
        profile.setProfileId(UUID.randomUUID());
        profile.setFamilyAccountId(familyAccountId);
        profile.setNickname(nickname.trim());
        profile.setAge(intValue(body.get("age")));
        profile.setGender(stringValue(body.get("gender")));
        profile.setInterests(stringValue(body.get("interests")));
        Instant now = Instant.now();
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        profileMapper.insert(profile);
        return profile;
    }

    /** 更新档案（归属校验：非本人档案拒绝）。 */
    public TocChildProfile updateProfile(UUID familyAccountId, UUID profileId, Map<String, Object> body) {
        TocChildProfile profile = requireOwned(familyAccountId, profileId);
        String nickname = stringValue(body.get("nickname"));
        if (nickname != null && !nickname.isBlank()) {
            profile.setNickname(nickname.trim());
        }
        if (body.containsKey("age")) {
            profile.setAge(intValue(body.get("age")));
        }
        if (body.containsKey("gender")) {
            profile.setGender(stringValue(body.get("gender")));
        }
        if (body.containsKey("interests")) {
            profile.setInterests(stringValue(body.get("interests")));
        }
        profile.setUpdatedAt(Instant.now());
        profileMapper.updateById(profile);
        return profile;
    }

    /** 删除档案（归属校验）。 */
    public void deleteProfile(UUID familyAccountId, UUID profileId) {
        requireOwned(familyAccountId, profileId);
        profileMapper.deleteById(profileId);
    }

    private TocChildProfile requireOwned(UUID familyAccountId, UUID profileId) {
        TocChildProfile profile = profileMapper.selectById(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("档案不存在");
        }
        if (!familyAccountId.equals(profile.getFamilyAccountId())) {
            throw new IllegalArgumentException("无权访问该档案");
        }
        return profile;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
