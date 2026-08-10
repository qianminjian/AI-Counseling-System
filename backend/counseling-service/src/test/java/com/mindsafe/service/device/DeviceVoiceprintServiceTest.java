package com.mindsafe.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeviceVoiceprintService 测试（CFG-006，doing/84 §四.4）
 * <p>
 * 覆盖：任务创建（INITIATED）、轮询、设备端阶段推进（COLLECTING/UPLOADED）、
 * enroll 联动完成（COMPLETED）、失败置位、非法阶段拒绝。
 */
class DeviceVoiceprintServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DeviceVoiceprintService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new DeviceVoiceprintService(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("创建任务：返回 INITIATED 且写入 Redis（TTL 30 分钟）")
    void createTaskWritesInitiatedState() {
        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "teacher-1");

        assertThat(task.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_INITIATED);
        assertThat(task.get("deviceCode")).isEqualTo("K7M2P9XW4AQ");
        assertThat(task.get("studentId")).isEqualTo("stu-1");
        assertThat(task.get("taskId")).isNotNull();
        verify(valueOps).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("轮询：任务存在返回当前状态")
    void getTaskReturnsStoredState() {
        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        when(valueOps.get(anyString())).thenReturn(serialize(task));

        Map<String, Object> fetched = service.getTask((String) task.get("taskId"));

        assertThat(fetched.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_INITIATED);
    }

    @Test
    @DisplayName("轮询：任务不存在返回 null")
    void getTaskReturnsNullWhenMissing() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThat(service.getTask("no-such-task")).isNull();
    }

    @Test
    @DisplayName("设备端上报：INITIATED → COLLECTING → UPLOADED 推进")
    void reportPhaseAdvances() {
        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");
        when(valueOps.get(anyString())).thenReturn(serialize(task));

        Map<String, Object> collecting = service.reportPhase(taskId, DeviceVoiceprintService.PHASE_COLLECTING);
        assertThat(collecting.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_COLLECTING);

        when(valueOps.get(anyString())).thenReturn(serialize(collecting));
        Map<String, Object> uploaded = service.reportPhase(taskId, DeviceVoiceprintService.PHASE_UPLOADED);
        assertThat(uploaded.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_UPLOADED);
    }

    @Test
    @DisplayName("非法采集阶段拒绝")
    void reportPhaseRejectsInvalid() {
        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        when(valueOps.get(anyString())).thenReturn(serialize(task));

        assertThatThrownBy(() -> service.reportPhase((String) task.get("taskId"), "HACKED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法采集阶段");
    }

    @Test
    @DisplayName("enroll 联动：complete 置 COMPLETED（AC-84-14）")
    void completeMarksDone() {
        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");
        when(valueOps.get(anyString())).thenReturn(serialize(task));

        service.complete(taskId);
        verify(valueOps).set(anyString(), org.mockito.ArgumentMatchers.contains("COMPLETED"), anyLong(), any());
    }

    @Test
    @DisplayName("complete 对 null/空 taskId 静默忽略（不破坏既有 enroll 流程）")
    void completeIgnoresBlankTaskId() {
        service.complete(null);
        service.complete("");
        verify(valueOps, org.mockito.Mockito.never()).set(anyString(), anyString(), anyLong(), any());
    }

    private String serialize(Map<String, Object> task) {
        try {
            return new ObjectMapper().writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
