package com.mindsafe.service.monitoring;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mindsafe.common.dto.ErrorCode;
import com.mindsafe.common.exception.BizException;
import com.mindsafe.domain.entity.DegradationEvent;
import com.mindsafe.domain.mapper.DegradationEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级矩阵与手动切换单元测试（ADMIN-P2-01/02，AC-P2-01/02）
 * 覆盖：矩阵聚合（覆盖态/最近事件）/手动切换写键+manual 事件/取消覆盖/非法目标拒绝/事件时间线
 */
class DegradationMatrixServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final DegradationEventMapper eventMapper = mock(DegradationEventMapper.class);
    private final DegradationMatrixService service =
            new DegradationMatrixService(redisTemplate, eventMapper);

    {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("矩阵：覆盖态展示（override 存在时显示覆盖目标）")
    void matrixShowsOverride() {
        when(valueOps.get(DegradationMatrixService.OVERRIDE_KEY_PREFIX + "tts")).thenReturn("edge_tts");
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var matrix = service.matrix();

        assertThat(matrix).hasSize(DegradationMatrixService.POINTS.size());
        var tts = matrix.stream().filter(r -> "tts".equals(r.get("point"))).findFirst().orElseThrow();
        assertThat(tts.get("overridden")).isEqualTo(true);
        assertThat(tts.get("overrideTo")).isEqualTo("edge_tts");
    }

    @Test
    @DisplayName("手动切换：写 Redis 键 + degradation_events manual 事件（operator/detail 留痕）")
    void overrideWritesKeyAndEvent() {
        service.override("tts", "edge_tts", "ops", "强制切 edge 保稳定");

        verify(valueOps).set("mindsafe:degradation:override:tts", "edge_tts");
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(eventMapper).insert(captor.capture());
        DegradationEvent event = captor.getValue();
        assertThat(event.getPoint()).isEqualTo("tts");
        assertThat(event.getToState()).isEqualTo("edge_tts");
        assertThat(event.getTriggerType()).isEqualTo(DegradationEvent.TRIGGER_MANUAL);
        assertThat(event.getOperator()).isEqualTo("ops");
        assertThat(event.getDetail()).isEqualTo("强制切 edge 保稳定");
    }

    @Test
    @DisplayName("无效切换目标 → 拒绝")
    void overrideInvalidTargetRejected() {
        assertThatThrownBy(() -> service.override("tts", "nonsense", "ops", "x"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.PARAM_INVALID.code()));
    }

    @Test
    @DisplayName("未知降级点 → 拒绝")
    void overrideUnknownPointRejected() {
        assertThatThrownBy(() -> service.override("unknown-point", "x", "ops", "x"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("取消覆盖：删键 + manual 事件（to=default）")
    void cancelOverrideDeletesKey() {
        when(valueOps.get("mindsafe:degradation:override:ser")).thenReturn("disabled");

        service.cancelOverride("ser", "ops", "恢复默认");

        verify(redisTemplate).delete("mindsafe:degradation:override:ser");
        ArgumentCaptor<DegradationEvent> captor = ArgumentCaptor.forClass(DegradationEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getToState()).isEqualTo("default");
    }

    @Test
    @DisplayName("取消无覆盖点 → 拒绝")
    void cancelWithoutOverrideRejected() {
        when(valueOps.get("mindsafe:degradation:override:llm")).thenReturn(null);

        assertThatThrownBy(() -> service.cancelOverride("llm", "ops", "x"))
                .isInstanceOf(BizException.class);
    }
}
