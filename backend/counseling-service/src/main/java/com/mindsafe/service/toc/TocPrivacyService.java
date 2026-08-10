package com.mindsafe.service.toc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.TocChildProfileMapper;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * toC 隐私控制服务（doing/85 TOC-007，toC-AC-7）
 * <p>
 * 家长可查看/删除数据、关闭设备（解绑即停止使用）。删除为不可逆操作：
 * 解绑全部家庭设备 + 删除全部孩子档案 + 账号置 DISABLED（登录拒绝）。
 */
@Service
public class TocPrivacyService {

    private final TocFamilyAccountMapper accountMapper;
    private final TocChildProfileMapper profileMapper;
    private final TocDeviceService tocDeviceService;

    public TocPrivacyService(TocFamilyAccountMapper accountMapper,
                             TocChildProfileMapper profileMapper,
                             TocDeviceService tocDeviceService) {
        this.accountMapper = accountMapper;
        this.profileMapper = profileMapper;
        this.tocDeviceService = tocDeviceService;
    }

    /** 数据清单预览（账号/档案数/设备数，TOC-007 查看）。 */
    public Map<String, Object> getDataOverview(UUID familyAccountId) {
        TocFamilyAccount account = requireActive(familyAccountId);
        long profileCount = profileMapper.selectCount(
                new LambdaQueryWrapper<TocChildProfile>()
                        .eq(TocChildProfile::getFamilyAccountId, familyAccountId));
        long deviceCount = tocDeviceService.listDevices(familyAccountId).size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("familyAccountId", familyAccountId);
        result.put("phone", account.getPhone());
        result.put("status", account.getStatus());
        result.put("profileCount", profileCount);
        result.put("deviceCount", deviceCount);
        result.put("dataRetentionNote", "删除后数据不可恢复（TOC-007）");
        return result;
    }

    /**
     * 删除全部家庭数据（不可逆，TOC-007/toC-AC-7）：
     * 解绑全部设备 + 删除全部档案 + 账号 DISABLED（登录拒绝）。
     */
    public Map<String, Object> deleteAllData(UUID familyAccountId) {
        requireActive(familyAccountId);

        // 1. 解绑全部家庭设备
        List<Map<String, Object>> devices = tocDeviceService.listDevices(familyAccountId);
        int unboundCount = 0;
        for (Map<String, Object> d : devices) {
            String deviceCode = String.valueOf(d.get("deviceCode"));
            try {
                tocDeviceService.unbind(familyAccountId, deviceCode, familyAccountId.toString());
                unboundCount++;
            } catch (IllegalArgumentException ignored) {
                // 已解绑/设备异常：跳过
            }
        }

        // 2. 删除全部孩子档案
        List<TocChildProfile> profiles = profileMapper.selectList(
                new LambdaQueryWrapper<TocChildProfile>()
                        .eq(TocChildProfile::getFamilyAccountId, familyAccountId));
        for (TocChildProfile p : profiles) {
            profileMapper.deleteById(p.getProfileId());
        }

        // 3. 账号 DISABLED（登录拒绝，数据不可恢复）
        TocFamilyAccount update = new TocFamilyAccount();
        update.setFamilyAccountId(familyAccountId);
        update.setStatus(TocFamilyAccount.STATUS_DISABLED);
        update.setUpdatedAt(Instant.now());
        accountMapper.updateById(update);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("familyAccountId", familyAccountId);
        result.put("unboundDevices", unboundCount);
        result.put("deletedProfiles", profiles.size());
        result.put("accountStatus", TocFamilyAccount.STATUS_DISABLED);
        result.put("note", "数据已删除且不可恢复");
        return result;
    }

    private TocFamilyAccount requireActive(UUID familyAccountId) {
        TocFamilyAccount account = accountMapper.selectById(familyAccountId);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在");
        }
        if (!TocFamilyAccount.STATUS_ACTIVE.equals(account.getStatus())) {
            throw new IllegalArgumentException("账号已禁用");
        }
        return account;
    }
}
