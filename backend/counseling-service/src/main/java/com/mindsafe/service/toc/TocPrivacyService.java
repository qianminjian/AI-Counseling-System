package com.mindsafe.service.toc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.TocChildProfile;
import com.mindsafe.domain.entity.TocFamilyAccount;
import com.mindsafe.domain.mapper.TocChildProfileMapper;
import com.mindsafe.domain.mapper.TocFamilyAccountMapper;
import com.mindsafe.service.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>
 * 专题 E（P0-2）：删除为不可逆三写操作——同事务提交（部分失败整体回滚）；
 * 解绑异常不再静默（记录日志）；删除完成后写审计（与专题 D 审计基建联动）。
 */
@Service
public class TocPrivacyService {

    private static final Logger log = LoggerFactory.getLogger(TocPrivacyService.class);

    private final TocFamilyAccountMapper accountMapper;
    private final TocChildProfileMapper profileMapper;
    private final TocDeviceService tocDeviceService;
    private final AuditLogService auditLogService;

    public TocPrivacyService(TocFamilyAccountMapper accountMapper,
                             TocChildProfileMapper profileMapper,
                             TocDeviceService tocDeviceService,
                             AuditLogService auditLogService) {
        this.accountMapper = accountMapper;
        this.profileMapper = profileMapper;
        this.tocDeviceService = tocDeviceService;
        this.auditLogService = auditLogService;
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
     * <p>
     * 事务边界（专题 E P0-2）：三步不可逆删除同事务，任一步失败整体回滚，
     * 避免「设备已解绑但档案残留」等部分删除态。
     */
    @Transactional
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
            } catch (BizException e) {
                // E-P0-2：已解绑/设备异常跳过（业务语义不变），但记录日志不再静默吞异常
                // （code-engineering §4.4 禁空 catch）；若为 DB 异常应抛出回滚而非跳过
                log.warn("删除家庭数据-设备解绑跳过: familyAccountId={}, deviceCode={}, reason={}",
                        familyAccountId, deviceCode, e.getMessage());
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

        // E-P0-2：不可逆删除审计落库（与专题 D 审计基建联动）——toC 账号为平台级
        // （tenantId=null，TocAuthService 注释），审计写入需显式系统作用域以通过租户行隔离；
        // 审计为 @Async 独立事务，不随本事务回滚（与既有审计模式一致，审计失败不影响删除主流程）
        final int finalUnbound = unboundCount;
        final int finalDeleted = profiles.size();
        TenantContextHolder.callAsSystem(() -> {
            auditLogService.log(null, null, "TOC_DATA_DELETE", "toc_family_account", familyAccountId,
                    "删除全部家庭数据（TOC-007 不可逆）: 解绑设备 " + finalUnbound + " 台, 删除档案 " + finalDeleted + " 个");
            return null;
        });
        log.info("家庭数据删除完成: familyAccountId={}, unboundDevices={}, deletedProfiles={}",
                familyAccountId, unboundCount, profiles.size());
        return result;
    }

    private TocFamilyAccount requireActive(UUID familyAccountId) {
        TocFamilyAccount account = accountMapper.selectById(familyAccountId);
        if (account == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "账号不存在");
        }
        if (!TocFamilyAccount.STATUS_ACTIVE.equals(account.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return account;
    }
}
