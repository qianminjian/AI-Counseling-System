package com.mindsafe.service.config;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.SysConfig;
import com.mindsafe.domain.entity.SysConfigHistory;
import com.mindsafe.domain.mapper.SysConfigHistoryMapper;
import com.mindsafe.domain.mapper.SysConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置注册表服务单元测试（ADMIN-P1-01，AC-P1-01）
 * 覆盖：SECRET 掩码/RESTART 拒绝修改/变更留痕（reason 必填入历史）
 */
class SysConfigServiceTest {

    private final SysConfigMapper configMapper = mock(SysConfigMapper.class);
    private final SysConfigHistoryMapper historyMapper = mock(SysConfigHistoryMapper.class);
    private final SysConfigService service = new SysConfigService(configMapper, historyMapper);

    private SysConfig config(String key, String sensitive, String effectMode) {
        SysConfig config = new SysConfig();
        config.setConfigId(UUID.randomUUID());
        config.setConfigKey(key);
        config.setDomain("security");
        config.setSensitive(sensitive);
        config.setEffectMode(effectMode);
        config.setValue("old-value");
        return config;
    }

    @Test
    @DisplayName("HOT 配置修改 → 更新值 + 写入变更历史（old/new/reason/operator）")
    void updateHotConfigWritesHistory() {
        SysConfig config = config("mindsafe.test.hot", SysConfig.SENSITIVE_NORMAL, SysConfig.EFFECT_HOT);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(config);

        SysConfig result = service.update("mindsafe.test.hot", "new-value", "调优", "ops");

        assertThat(result.getValue()).isEqualTo("new-value");
        ArgumentCaptor<SysConfigHistory> captor = ArgumentCaptor.forClass(SysConfigHistory.class);
        verify(historyMapper).insert(captor.capture());
        SysConfigHistory history = captor.getValue();
        assertThat(history.getConfigKey()).isEqualTo("mindsafe.test.hot");
        assertThat(history.getOldValue()).isEqualTo("old-value");
        assertThat(history.getNewValue()).isEqualTo("new-value");
        assertThat(history.getReason()).isEqualTo("调优");
        assertThat(history.getChangedBy()).isEqualTo("ops");
    }

    @Test
    @DisplayName("RESTART 配置 → 拒绝在线修改（R-3：只读 + 重启指引）")
    void updateRestartConfigRejected() {
        SysConfig config = config("mindsafe.test.restart", SysConfig.SENSITIVE_NORMAL, SysConfig.EFFECT_RESTART);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(config);

        assertThatThrownBy(() -> service.update("mindsafe.test.restart", "x", "why", "ops"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("RESTART 生效");
    }

    @Test
    @DisplayName("SECRET 配置修改 → 历史 new_value 存掩码标记（值不回读）")
    void updateSecretConfigStoresMask() {
        SysConfig config = config("mindsafe.test.secret", SysConfig.SENSITIVE_SECRET, SysConfig.EFFECT_HOT);
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(config);

        service.update("mindsafe.test.secret", "real-secret-value", "轮换密钥", "ops");

        ArgumentCaptor<SysConfigHistory> captor = ArgumentCaptor.forClass(SysConfigHistory.class);
        verify(historyMapper).insert(captor.capture());
        assertThat(captor.getValue().getNewValue()).isEqualTo(SysConfig.SECRET_MASK);
    }

    @Test
    @DisplayName("配置不存在 → PARAM_INVALID")
    void updateUnknownConfig() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.update("ghost.key", "x", "why", "ops"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.PARAM_INVALID.code()));
    }
}
