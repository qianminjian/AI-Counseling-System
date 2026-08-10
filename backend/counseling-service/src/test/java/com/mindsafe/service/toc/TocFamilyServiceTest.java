package com.mindsafe.service.toc;

import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.domain.mapper.TocChildProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocFamilyService 测试（doing/85 TOC-002）
 * 覆盖：档案列表（隔离）、创建（昵称必填）、更新/删除（跨账号访问拒绝）。
 */
class TocFamilyServiceTest {

    private TocChildProfileMapper profileMapper;
    private TocFamilyService service;

    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        profileMapper = mock(TocChildProfileMapper.class);
        service = new TocFamilyService(profileMapper);
    }

    private TocChildProfile ownedProfile() {
        TocChildProfile p = new TocChildProfile();
        p.setProfileId(UUID.randomUUID());
        p.setFamilyAccountId(accountId);
        p.setNickname("小明");
        return p;
    }

    @Test
    @DisplayName("list：仅返回本人账号档案")
    void listIsolated() {
        when(profileMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listProfiles(accountId)).isEmpty();
    }

    @Test
    @DisplayName("create：昵称必填")
    void createRequiresNickname() {
        assertThatThrownBy(() -> service.createProfile(accountId, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("昵称");
    }

    @Test
    @DisplayName("create：创建成功并归属当前账号")
    void createOk() {
        TocChildProfile p = service.createProfile(accountId,
                Map.of("nickname", "小明", "age", 8, "gender", "MALE", "interests", "恐龙,画画"));
        assertThat(p.getFamilyAccountId()).isEqualTo(accountId);
        assertThat(p.getNickname()).isEqualTo("小明");
        assertThat(p.getAge()).isEqualTo(8);
        verify(profileMapper).insert(any(TocChildProfile.class));
    }

    @Test
    @DisplayName("update：跨账号访问拒绝（数据隔离）")
    void updateCrossAccountDenied() {
        TocChildProfile other = ownedProfile();
        other.setFamilyAccountId(UUID.randomUUID());
        when(profileMapper.selectById(other.getProfileId())).thenReturn(other);
        assertThatThrownBy(() -> service.updateProfile(accountId, other.getProfileId(), Map.of("nickname", "X")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    @DisplayName("delete：本人档案删除成功")
    void deleteOwnedOk() {
        TocChildProfile p = ownedProfile();
        when(profileMapper.selectById(p.getProfileId())).thenReturn(p);
        service.deleteProfile(accountId, p.getProfileId());
        verify(profileMapper).deleteById(p.getProfileId());
    }

    @Test
    @DisplayName("delete：跨账号拒绝且不删除")
    void deleteCrossAccountDenied() {
        TocChildProfile other = ownedProfile();
        other.setFamilyAccountId(UUID.randomUUID());
        when(profileMapper.selectById(other.getProfileId())).thenReturn(other);
        assertThatThrownBy(() -> service.deleteProfile(accountId, other.getProfileId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
        verify(profileMapper, org.mockito.Mockito.never()).deleteById(any(UUID.class));
    }
}
