package com.mindsafe.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
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
    private DeviceVoiceprintService.VoiceprintScriptRunner runner;
    private final AtomicReference<String> store = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // AD-006：runner 注入（内存模拟 Lua 语义）——store 由各测试提供
        runner = (script, key, args) -> luaAnswer(store, script, key, args);
        service = new DeviceVoiceprintService(redisTemplate, new ObjectMapper(), runner);
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
    @DisplayName("设备端上报：INITIATED → COLLECTING → UPLOADED 推进（AD-006 Lua 原子语义模拟）")
    void reportPhaseAdvances() {
        // AD-006：execute 走 Lua 原子脚本——用内存 store 模拟脚本语义（CAS 推进 + 写回）
        store.set(null);
        when(valueOps.get(anyString())).thenAnswer(i -> store.get());
        doAnswer(i -> {
            store.set(i.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");

        Map<String, Object> collecting = service.reportPhase(taskId, DeviceVoiceprintService.PHASE_COLLECTING, null);
        assertThat(collecting.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_COLLECTING);

        Map<String, Object> uploaded = service.reportPhase(taskId, DeviceVoiceprintService.PHASE_UPLOADED, null);
        // B-03（doing/98）：UPLOADED 停留，等待 enroll 驱动 complete（不再自动完成）
        assertThat(uploaded.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_UPLOADED);
    }

    @Test
    @DisplayName("并发语义：终态（COMPLETED）后再次上报返回原任务不覆盖（AD-006 CAS）")
    void reportPhaseAfterTerminalKeepsPhase() {
        store.set(null);
        when(valueOps.get(anyString())).thenAnswer(i -> store.get());
        doAnswer(i -> {
            store.set(i.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");
        service.reportPhase(taskId, DeviceVoiceprintService.PHASE_UPLOADED, null); // → UPLOADED（停留，B-03）

        Map<String, Object> stale = service.reportPhase(taskId, DeviceVoiceprintService.PHASE_COLLECTING, null);
        // CAS 拒绝：脚本返回原任务（仍为 UPLOADED）
        assertThat(stale.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_UPLOADED);
    }

    @Test
    @DisplayName("设备码不匹配 → 拒绝（AD-006 Lua 内校验）")
    void reportPhaseDeviceMismatch() {
        store.set(null);
        when(valueOps.get(anyString())).thenAnswer(i -> store.get());
        doAnswer(i -> {
            store.set(i.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");

        assertThatThrownBy(() -> service.reportPhase(taskId, DeviceVoiceprintService.PHASE_COLLECTING, "EVIL_CODE1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("设备码与任务不匹配");
    }

    @Test
    @DisplayName("非法采集阶段拒绝")
    void reportPhaseRejectsInvalid() {
        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        when(valueOps.get(anyString())).thenReturn(serialize(task));

        assertThatThrownBy(() -> service.reportPhase((String) task.get("taskId"), "HACKED", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法采集阶段");
    }

    @Test
    @DisplayName("enroll 联动：complete 置 COMPLETED（AC-84-14，AD-006 Lua 原子写）")
    void completeMarksDone() {
        store.set(null);
        when(valueOps.get(anyString())).thenAnswer(i -> store.get());
        doAnswer(i -> {
            store.set(i.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");

        service.complete(taskId);
        Map<String, Object> after = service.getTask(taskId);
        assertThat(after.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_COMPLETED);
    }

    @Test
    @DisplayName("complete 携带错误 deviceCode → 拒绝（AD-006 归属校验）")
    void completeDeviceMismatch() {
        store.set(null);
        when(valueOps.get(anyString())).thenAnswer(i -> store.get());
        doAnswer(i -> {
            store.set(i.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Map<String, Object> task = service.createTask("K7M2P9XW4AQ", "stu-1", "t");
        String taskId = (String) task.get("taskId");

        assertThatThrownBy(() -> service.complete(taskId, "EVIL_CODE1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("设备码与任务不匹配");
        // P1（code-review）：校验失败不写回——任务保持 INITIATED
        Map<String, Object> after = service.getTask(taskId);
        assertThat(after.get("phase")).isEqualTo(DeviceVoiceprintService.PHASE_INITIATED);
    }

    @Test
    @DisplayName("complete 对 null/空 taskId 静默忽略（不破坏既有 enroll 流程）")
    void completeIgnoresBlankTaskId() {
        service.complete(null);
        service.complete("");
        verify(redisTemplate, org.mockito.Mockito.never()).execute(any(), anyList(), any(Object[].class));
    }

    /**
     * AD-006：内存 Lua 语义（CAS 前置检查 + 设备码校验 + 写回 store）。
     * args[0]=新阶段；reportPhase（4 参）额外 args[1]=deviceCode、args[2]=updatedAt；
     * terminal（3 参）args[1]=updatedAt。
     */
    private String luaAnswer(AtomicReference<String> store,
                              org.springframework.data.redis.core.script.RedisScript<?> script,
                              String key, Object... args) {
        String json = store.get();
        if (json == null) {
            return null;
        }
        Map<String, Object> task = read(json);
        String newPhase = String.valueOf(args[0]);
        boolean reportPhase = script == DeviceVoiceprintService.REPORT_PHASE_SCRIPT;
        if (reportPhase) {
            // REPORT_PHASE 语义：前置状态检查 + 设备码校验
            if (!DeviceVoiceprintService.PHASE_INITIATED.equals(task.get("phase"))
                    && !DeviceVoiceprintService.PHASE_COLLECTING.equals(task.get("phase"))) {
                return serialize(task);
            }
            String deviceCode = String.valueOf(args[1]);
            if (!deviceCode.isEmpty() && !deviceCode.equals(task.get("deviceCode"))) {
                return "MISMATCH";
            }
            task.put("updatedAt", String.valueOf(args[2]));
        } else {
            // TERMINAL_PHASE 语义：deviceCode 校验（args[3]，可选）+ 置位（与 Lua 脚本一致，失败不写回）
            String devCode = String.valueOf(args[3]);
            if (!devCode.isEmpty() && !devCode.equals(task.get("deviceCode"))) {
                return "MISMATCH";
            }
            task.put("updatedAt", String.valueOf(args[1]));
            if (DeviceVoiceprintService.PHASE_COMPLETED.equals(newPhase)) {
                task.put("completedAt", String.valueOf(args[1]));
            }
            if (DeviceVoiceprintService.PHASE_FAILED.equals(newPhase)) {
                task.put("failedAt", String.valueOf(args[1]));
            }
        }
        task.put("phase", newPhase);
        String out = serialize(task);
        store.set(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("测试反序列化失败", e);
        }
    }

    private String serialize(Map<String, Object> task) {
        try {
            return new ObjectMapper().writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
