package com.mindsafe.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 无屏终端声纹录入编排（CFG-006，doing/84 §四.4）
 * <p>
 * 任务状态机（Redis 存储，TTL 30 分钟）：INITIATED → COLLECTING → UPLOADED → COMPLETED；
 * FAILED 可重试（重试 = 新建任务）。真实 voice-service 采集对接由设备端固件完成，
 * 本服务负责管理侧编排（发起/轮询）与设备端进度上报（report/voiceprint）。
 */
@Service
public class DeviceVoiceprintService {

    /** 任务 TTL（分钟） */
    private static final long TASK_TTL_MINUTES = 30L;

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "device:voiceprint:";

    /** 任务阶段：已发起（管理台创建） */
    public static final String PHASE_INITIATED = "INITIATED";

    /** 任务阶段：采集中（设备端上报） */
    public static final String PHASE_COLLECTING = "COLLECTING";

    /** 任务阶段：已上传 embeddings（设备端上报） */
    public static final String PHASE_UPLOADED = "UPLOADED";

    /** 任务阶段：已完成（enroll 落库后置位） */
    public static final String PHASE_COMPLETED = "COMPLETED";

    /** 任务阶段：失败（可重试，重试 = 新建任务） */
    public static final String PHASE_FAILED = "FAILED";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public DeviceVoiceprintService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发起声纹录入任务（AC-84-13）：管理台/绑定后引导调用，返回 taskId 供页面轮询。
     */
    public Map<String, Object> createTask(String deviceCode, String studentId, String operator) {
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId);
        task.put("deviceCode", deviceCode);
        task.put("studentId", studentId);
        task.put("operator", operator);
        task.put("phase", PHASE_INITIATED);
        task.put("createdAt", Instant.now().toString());
        redisTemplate.opsForValue().set(key(taskId), write(task), TASK_TTL_MINUTES, TimeUnit.MINUTES);
        return task;
    }

    /**
     * 轮询任务状态（AC-84-14）：页面 3s 间隔查询；任务不存在返回 null。
     */
    public Map<String, Object> getTask(String taskId) {
        String json = redisTemplate.opsForValue().get(key(taskId));
        return json == null ? null : read(json);
    }

    /**
     * 设备端采集进度上报（AC-84-13）：COLLECTING/UPLOADED 阶段推进；任务不存在返回 null。
     * 当设备上报 UPLOADED 时自动调用 complete() 完成状态机流转（P0-5：真实 enroll
     * 落库链路待对接 voice-service，当前前置亭——前端轮询不会卡在 UPLOADED）。
     */
    public Map<String, Object> reportPhase(String taskId, String phase, String deviceCode) {
        Map<String, Object> task = getTask(taskId);
        if (task == null || !task.get("phase").equals(PHASE_INITIATED) && !task.get("phase").equals(PHASE_COLLECTING)) {
            return task;
        }
        if (!PHASE_COLLECTING.equals(phase) && !PHASE_UPLOADED.equals(phase)) {
            throw new IllegalArgumentException("非法采集阶段: " + phase);
        }
        // P0-5：校验 task 的 deviceCode 与上报方一致
        if (deviceCode != null && !deviceCode.equals(task.get("deviceCode"))) {
            throw new IllegalArgumentException("设备码与任务不匹配");
        }
        task.put("phase", phase);
        task.put("updatedAt", Instant.now().toString());
        redisTemplate.opsForValue().set(key(taskId), write(task), TASK_TTL_MINUTES, TimeUnit.MINUTES);
        // P0-5：UPLOADED 自动触发 complete（真实 enroll 链路就绪后替换为唤醒 enroll 异步任务）
        if (PHASE_UPLOADED.equals(phase)) {
            complete(taskId);
            return getTask(taskId);
        }
        return task;
    }

    /**
     * 标记任务完成（AC-84-14）：enroll 落库成功后调用；任务不存在静默忽略。
     * @TODO P0-5：当前由 reportPhase(UPLOADED) 自动触发 complete()，真实 enroll
     * 对接 voice-service 后应移除自动逻辑，改由 enroll 回调驱动。
     */
    public void complete(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Map<String, Object> task = getTask(taskId);
        if (task == null) {
            return;
        }
        task.put("phase", PHASE_COMPLETED);
        task.put("completedAt", Instant.now().toString());
        redisTemplate.opsForValue().set(key(taskId), write(task), TASK_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /** 标记任务失败（可重试：客户端新建任务） */
    public void fail(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Map<String, Object> task = getTask(taskId);
        if (task == null) {
            return;
        }
        task.put("phase", PHASE_FAILED);
        task.put("failedAt", Instant.now().toString());
        redisTemplate.opsForValue().set(key(taskId), write(task), TASK_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private static String key(String taskId) {
        return KEY_PREFIX + taskId;
    }

    private String write(Map<String, Object> task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("声纹任务序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalStateException("声纹任务反序列化失败", e);
        }
    }
}
