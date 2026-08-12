package com.mindsafe.api.controller;

import com.mindsafe.common.exception.BizException;
import com.mindsafe.api.security.JwtAuthenticationFilter.TenantContext;
import com.mindsafe.service.toc.TocPrivacyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocPrivacyController 测试（doing/85 TOC-007）
 * 覆盖：数据预览、删除（X-Confirm 二次确认门禁）。
 */
class TocPrivacyControllerTest {

    private TocPrivacyService tocPrivacyService;
    private TocPrivacyController controller;

    private final UUID familyAccountId = UUID.randomUUID();
    private final Authentication auth = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        tocPrivacyService = mock(TocPrivacyService.class);
        controller = new TocPrivacyController(tocPrivacyService);
        when(auth.getDetails()).thenReturn(new TenantContext(null, familyAccountId, "toc_parent"));
    }

    @Test
    @DisplayName("overview：返回数据清单")
    void overviewOk() {
        when(tocPrivacyService.getDataOverview(familyAccountId)).thenReturn(Map.of("deviceCount", 1));
        var response = controller.overview(auth);
        assertThat(response.code()).isEqualTo(0);
        assertThat(response.data().get("deviceCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("delete：缺 X-Confirm 二次确认 → 400")
    void deleteRequiresConfirm() {
        assertThatThrownBy(() -> controller.deleteAll(auth, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("X-Confirm");
    }

    @Test
    @DisplayName("delete：二次确认后删除成功")
    void deleteOk() {
        when(tocPrivacyService.deleteAllData(familyAccountId)).thenReturn(Map.of("accountStatus", "DISABLED"));
        var response = controller.deleteAll(auth, "CONFIRM");
        assertThat(response.code()).isEqualTo(0);
        verify(tocPrivacyService).deleteAllData(familyAccountId);
    }
}
