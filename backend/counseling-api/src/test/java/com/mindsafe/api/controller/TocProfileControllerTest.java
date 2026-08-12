package com.mindsafe.api.controller;

import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.service.toc.TocChildProfileCreateDTO;
import com.mindsafe.service.toc.TocFamilyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocProfileController 测试（F23，doing/97：原唯一无测试 controller）
 * 覆盖：list/create/update/delete 基础端点 + 错误映射（400）+ 数据隔离（familyAccountId 取自 token 上下文）。
 */
class TocProfileControllerTest {

    private TocFamilyService tocFamilyService;
    private TocProfileController controller;
    private Authentication auth;
    private UUID familyAccountId;

    @BeforeEach
    void setUp() {
        tocFamilyService = mock(TocFamilyService.class);
        controller = new TocProfileController(tocFamilyService);
        familyAccountId = UUID.randomUUID();
        auth = mock(Authentication.class);
        when(auth.getDetails()).thenReturn(new TenantContext(familyAccountId, familyAccountId, "toc_parent"));
    }

    @Test
    @DisplayName("list：返回本人账号档案 VO 列表")
    void listOk() {
        TocChildProfile p = new TocChildProfile();
        p.setProfileId(UUID.randomUUID());
        p.setNickname("小明");
        when(tocFamilyService.listProfiles(familyAccountId)).thenReturn(List.of(p));

        var response = controller.list(auth);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).nickname()).isEqualTo("小明");
        verify(tocFamilyService).listProfiles(familyAccountId);
    }

    @Test
    @DisplayName("create：成功 → 返回档案 VO（familyAccountId 取自 token 上下文）")
    void createOk() {
        TocChildProfile p = new TocChildProfile();
        p.setProfileId(UUID.randomUUID());
        p.setFamilyAccountId(familyAccountId);
        p.setNickname("小红");
        TocChildProfileCreateDTO dto = new TocChildProfileCreateDTO("小红", 7, "FEMALE", "画画");
        when(tocFamilyService.createProfile(familyAccountId, dto)).thenReturn(p);

        var response = controller.create(auth, dto);

        assertThat(response.data().nickname()).isEqualTo("小红");
        verify(tocFamilyService).createProfile(familyAccountId, dto);
    }

    @Test
    @DisplayName("update：成功 → 返回更新后档案 VO")
    void updateOk() {
        UUID profileId = UUID.randomUUID();
        TocChildProfile p = new TocChildProfile();
        p.setProfileId(profileId);
        p.setNickname("小红改");
        Map<String, Object> body = Map.of("nickname", "小红改");
        when(tocFamilyService.updateProfile(familyAccountId, profileId, body)).thenReturn(p);

        var response = controller.update(auth, profileId, body);

        assertThat(response.data().nickname()).isEqualTo("小红改");
        verify(tocFamilyService).updateProfile(familyAccountId, profileId, body);
    }

    @Test
    @DisplayName("delete：成功返回 ok；service 抛 IllegalArgumentException → 400")
    void deleteOkAndErrorMapping() {
        UUID profileId = UUID.randomUUID();

        var response = controller.delete(auth, profileId);
        assertThat(response.data()).isNull();
        verify(tocFamilyService).deleteProfile(familyAccountId, profileId);

        org.mockito.Mockito.doThrow(new IllegalArgumentException("档案不存在"))
                .when(tocFamilyService).deleteProfile(familyAccountId, profileId);
        assertThatThrownBy(() -> controller.delete(auth, profileId))
                .isInstanceOf(BizException.class);
    }
}
