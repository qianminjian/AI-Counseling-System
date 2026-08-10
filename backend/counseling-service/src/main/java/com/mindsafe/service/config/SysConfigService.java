package com.mindsafe.service.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.common.tenant.TenantContextHolder;
import com.mindsafe.domain.entity.SysConfig;
import com.mindsafe.domain.entity.SysConfigHistory;
import com.mindsafe.domain.mapper.SysConfigHistoryMapper;
import com.mindsafe.domain.mapper.SysConfigMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 配置注册表服务（ADMIN-P1-01，M1 系统配置管理）
 * <p>
 * 配置面板：SECRET 掩码（值永不出 API）+ HOT/RESTART 两级（R-3：仅标记 HOT
 * 开放修改，RESTART 只读 + 重启指引）+ 变更留痕（reason 必填）。
 * 设计见 doing/83 后台管理端 §5.1/§7.1。
 */
@Service
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;
    private final SysConfigHistoryMapper historyMapper;

    public SysConfigService(SysConfigMapper sysConfigMapper,
                            SysConfigHistoryMapper historyMapper) {
        this.sysConfigMapper = sysConfigMapper;
        this.historyMapper = historyMapper;
    }

    /** 配置注册表（分域过滤；SECRET 值掩码） */
    public List<SysConfig> listByDomain(String domain) {
        List<SysConfig> configs = sysConfigMapper.selectList(
                new LambdaQueryWrapper<SysConfig>()
                        .eq(domain != null && !domain.isBlank(), SysConfig::getDomain, domain)
                        .orderByAsc(SysConfig::getDomain, SysConfig::getConfigKey));
        // 平台表查询：租户拦截器豁免（IGNORE_TABLES 已含 sys_config，V36）
        return configs;
    }

    /** 单配置详情（SECRET 值掩码） */
    public SysConfig get(String key) {
        SysConfig config = sysConfigMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>().eq(SysConfig::getConfigKey, key));
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "配置不存在: " + key);
        }
        return config;
    }

    /**
     * 修改配置（仅 super_admin，reason 必填）。
     * R-3：仅标记 HOT 的配置开放修改；RESTART 类只读 + 重启指引。
     * SECRET 类只存掩码标记（值不可回读，更新时以掩码覆盖）。
     */
    public SysConfig update(String key, String value, String reason, String operator) {
        SysConfig config = get(key);
        if (!SysConfig.EFFECT_HOT.equals(config.getEffectMode())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "配置 [" + key + "] 为 RESTART 生效，不支持在线修改，请通过部署配置修改后重启");
        }

        SysConfigHistory history = new SysConfigHistory();
        history.setHistoryId(UUID.randomUUID());
        history.setConfigKey(key);
        history.setOldValue(config.getValue());
        history.setNewValue(SysConfig.SENSITIVE_SECRET.equals(config.getSensitive())
                ? SysConfig.SECRET_MASK : value);
        history.setChangedBy(operator);
        history.setReason(reason);
        history.setChangedAt(Instant.now());
        historyMapper.insert(history);

        // SECRET 类只落掩码标记（值不回读不落明文，code-review H3：原实现明文落库可经 GET 泄露）
        config.setValue(SysConfig.SENSITIVE_SECRET.equals(config.getSensitive())
                ? SysConfig.SECRET_MASK : value);
        config.setUpdatedAt(Instant.now());
        config.setUpdatedBy(operator);
        sysConfigMapper.updateById(config);
        return config;
    }

    /** 变更历史（倒序） */
    public List<SysConfigHistory> history(String key, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return historyMapper.selectList(new LambdaQueryWrapper<SysConfigHistory>()
                .eq(SysConfigHistory::getConfigKey, key)
                .orderByDesc(SysConfigHistory::getChangedAt)
                .last("LIMIT " + safeLimit));
    }
}
