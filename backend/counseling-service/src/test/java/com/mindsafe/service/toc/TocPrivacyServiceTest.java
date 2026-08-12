package com.mindsafe.service.toc;

import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.TocChildProfileMapper;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TocPrivacyService 测试（doing/85 TOC-007）
 * 覆盖：数据预览（档案/设备计数）、删除全部数据（解绑+删档案+账号 DISABLED+审计落库）、
 * 解绑异常跳过、已禁用账号拒绝访问。
 * 专题 E（P0-2）：删除写审计 + 解绑异常记录日志不再静默。
 */
class TocPrivacyServiceTest {

    private TocFamilyAccountMapper accountMapper;
    private TocChildProfileMapper profileMapper;
    private TocDeviceService tocDeviceService;
    private AuditLogService auditLogService;
    private TocPrivacyService service;

    private final UUID familyAccountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountMapper = mock(TocFamilyAccountMapper.class);
        profileMapper = mock(TocChildProfileMapper.class);
        tocDeviceService = mock(TocDeviceService.class);
        auditLogService = mock(AuditLogService.class);
        service = new TocPrivacyService(accountMapper, profileMapper, tocDeviceService, auditLogService);
    }

    private TocFamilyAccount activeAccount() {
        TocFamilyAccount a = new TocFamilyAccount();
        a.setFamilyAccountId(familyAccountId);
        a.setPhone("13800138000");
        a.setStatus(TocFamilyAccount.STATUS_ACTIVE);
        return a;
    }

    @Test
    @DisplayName("overview：返回账号信息 + 档案/设备计数")
    void overviewOk() {
        when(accountMapper.selectById(familyAccountId)).thenReturn(activeAccount());
        when(profileMapper.selectCount(any())).thenReturn(2L);
        when(tocDeviceService.listDevices(familyAccountId)).thenReturn(List.of(Map.of("deviceCode", "K7M2P9XW4AQ")));

        var result = service.getDataOverview(familyAccountId);

        assertThat(result.get("profileCount")).isEqualTo(2L);
        assertThat(result.get("deviceCount")).isEqualTo(1L);
        assertThat(result.get("status")).isEqualTo(TocFamilyAccount.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("deleteAllData：解绑设备 + 删档案 + 账号 DISABLED")
    void deleteAllDataOk() {
        when(accountMapper.selectById(familyAccountId)).thenReturn(activeAccount());
        when(tocDeviceService.listDevices(familyAccountId)).thenReturn(List.of(
                Map.of("deviceCode", "K7M2P9XW4AQ"), Map.of("deviceCode", "A1B2C3D4E5F")));
        TocChildProfile p1 = new TocChildProfile();
        p1.setProfileId(UUID.randomUUID());
        when(profileMapper.selectList(any())).thenReturn(List.of(p1));

        var result = service.deleteAllData(familyAccountId);

        assertThat(result.get("unboundDevices")).isEqualTo(2);
        assertThat(result.get("deletedProfiles")).isEqualTo(1);
        assertThat(result.get("accountStatus")).isEqualTo(TocFamilyAccount.STATUS_DISABLED);
        verify(tocDeviceService).unbind(familyAccountId, "K7M2P9XW4AQ", familyAccountId.toString());
        verify(tocDeviceService).unbind(familyAccountId, "A1B2C3D4E5F", familyAccountId.toString());
        verify(profileMapper).deleteById(p1.getProfileId());
        verify(accountMapper).updateById(any(TocFamilyAccount.class));
        // E-P0-2：不可逆删除写审计（平台级 tenantId=null，resourceType=toc_family_account）
        verify(auditLogService).log(isNull(), isNull(), eq("TOC_DATA_DELETE"),
                eq("toc_family_account"), eq(familyAccountId), any());
    }

    @Test
    @DisplayName("deleteAllData：单台设备解绑抛异常时跳过并继续，审计仍落库")
    void deleteAllDataSkipsFailedUnbind() {
        when(accountMapper.selectById(familyAccountId)).thenReturn(activeAccount());
        when(tocDeviceService.listDevices(familyAccountId)).thenReturn(List.of(
                Map.of("deviceCode", "BROKEN0001"), Map.of("deviceCode", "OK00000002")));
        when(tocDeviceService.unbind(familyAccountId, "BROKEN0001", familyAccountId.toString()))
                .thenThrow(new BizException(ErrorCode.RESOURCE_NOT_FOUND, "设备不存在"));
        TocChildProfile p1 = new TocChildProfile();
        p1.setProfileId(UUID.randomUUID());
        when(profileMapper.selectList(any())).thenReturn(List.of(p1));

        var result = service.deleteAllData(familyAccountId);

        // 解绑失败的设备被跳过，其余流程继续
        assertThat(result.get("unboundDevices")).isEqualTo(1);
        assertThat(result.get("deletedProfiles")).isEqualTo(1);
        verify(profileMapper).deleteById(p1.getProfileId());
        verify(accountMapper).updateById(any(TocFamilyAccount.class));
        verify(auditLogService).log(isNull(), isNull(), eq("TOC_DATA_DELETE"),
                eq("toc_family_account"), eq(familyAccountId), any());
    }

    @Test
    @DisplayName("deleteAllData：非参数异常（如 DB 异常）不吞，直接向上抛出（事务回滚）")
    void deleteAllDataPropagatesNonArgumentErrors() {
        when(accountMapper.selectById(familyAccountId)).thenReturn(activeAccount());
        when(tocDeviceService.listDevices(familyAccountId))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.deleteAllData(familyAccountId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");
        // 审计不得发出（删除未完成）
        verify(auditLogService, never()).log(isNull(), isNull(), eq("TOC_DATA_DELETE"),
                eq("toc_family_account"), eq(familyAccountId), any());
    }

    @Test
    @DisplayName("已禁用账号拒绝访问（删除不可重复执行）")
    void disabledAccountDenied() {
        TocFamilyAccount disabled = activeAccount();
        disabled.setStatus(TocFamilyAccount.STATUS_DISABLED);
        when(accountMapper.selectById(familyAccountId)).thenReturn(disabled);
        assertThatThrownBy(() -> service.deleteAllData(familyAccountId))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
