package com.mindsafe.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 无屏终端声纹录入编排（CFG-006，doing/84 §四.4）
 * <p>
 * 任务状态机（Redis 存储，TTL 30 分钟）：INITIATED → COLLECTING → UPLOADED → COMPLETED；
 * FAILED 可重试（重试 = 新建任务）。设备端固件负责采集与 embedding 上传，
 * 落库（enroll）由 VoiceprintController.enroll（携带 taskId）驱动并置位 COMPLETED
 * （B-03 doing/98：UPLOADED 不再自动 complete，任务完成语义与真实落库对齐）。
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

    /**
     * AD-006（2026-08-11）：阶段推进 Lua 原子脚本（CAS 防并发覆盖）——
     * 仅 INITIATED/COLLECTING 可推进；deviceCode 匹配校验；写回带 TTL。
     * 返回：新任务 JSON / 原任务 JSON（不可推进）/ 'MISMATCH' / nil（任务不存在）。
     */
    /** 包级可见（测试按脚本实例分发模拟，P3-3） */
    static final DefaultRedisScript<String> REPORT_PHASE_SCRIPT = new DefaultRedisScript<>(
            "local json = redis.call('GET', KEYS[1])\n"
                    + "if not json then return nil end\n"
                    + "local task = cjson.decode(json)\n"
                    + "if task.phase ~= 'INITIATED' and task.phase ~= 'COLLECTING' then return cjson.encode(task) end\n"
                    + "if ARGV[2] ~= '' and task.deviceCode ~= ARGV[2] then return 'MISMATCH' end\n"
                    + "task.phase = ARGV[1]\n"
                    + "task.updatedAt = ARGV[3]\n"
                    + "redis.call('SET', KEYS[1], cjson.encode(task), 'EX', tonumber(ARGV[4]))\n"
                    + "return cjson.encode(task)", String.class);

    /**
     * AD-006：终态原子写（COMPLETED/FAILED，管理端强制完成/失败；带 TTL 写回）。
     * code-review P1（2026-08-11）：deviceCode 校验在脚本内（ARGV[4] 可选）——
     * 不匹配返回 'MISMATCH' 不写回（与 REPORT_PHASE 语义一致，杜绝先写后验污染状态）。
     */
    /** 包级可见（测试按脚本实例分发模拟，P3-3） */
    static final DefaultRedisScript<String> TERMINAL_PHASE_SCRIPT = new DefaultRedisScript<>(
            "local json = redis.call('GET', KEYS[1])\n"
                    + "if not json then return nil end\n"
                    + "local task = cjson.decode(json)\n"
                    + "if ARGV[4] and ARGV[4] ~= '' and task.deviceCode ~= ARGV[4] then return 'MISMATCH' end\n"
                    + "task.phase = ARGV[1]\n"
                    + "task.updatedAt = ARGV[2]\n"
                    + "if ARGV[1] == 'COMPLETED' then task.completedAt = ARGV[2] end\n"
                    + "if ARGV[1] == 'FAILED' then task.failedAt = ARGV[2] end\n"
                    + "redis.call('SET', KEYS[1], cjson.encode(task), 'EX', tonumber(ARGV[3]))\n"
                    + "return cjson.encode(task)", String.class);

    /**
     * Lua 脚本执行器（AD-006，2026-08-11）：与 RedisTemplate 解耦——
     * 生产默认包装 execute；测试注入内存模拟（规避 Mockito varargs 匹配陷阱）。
     */
    @FunctionalInterface
    interface VoiceprintScriptRunner {
        String execute(DefaultRedisScript<String> script, String key, Object... args);
    }

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VoiceprintScriptRunner scriptRunner;

    // @Autowired 显式标注：多构造器时 Spring 按此注入（AD-006 引入 3 参包级构造器后 Spring 无法自动选择）
    @Autowired
    public DeviceVoiceprintService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, null);
    }

    DeviceVoiceprintService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                            VoiceprintScriptRunner scriptRunner) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.scriptRunner = scriptRunner != null ? scriptRunner
                : (script, key, args) -> redisTemplate.execute(script, List.of(key), args);
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
     * B-03（doing/98）：UPLOADED 后停留在该阶段，等待 enroll（VoiceprintController.enroll
     * 携带 taskId 落库成功后）置位 COMPLETED——移除原「UPLOADED 自动 complete」半接线，
     * 任务完成语义与真实 embedding 落库对齐。
     */
    public Map<String, Object> reportPhase(String taskId, String phase, String deviceCode) {
        if (!PHASE_COLLECTING.equals(phase) && !PHASE_UPLOADED.equals(phase)) {
            throw new IllegalArgumentException("非法采集阶段: " + phase);
        }
        // AD-006：Lua 原子推进（CAS）——读取/校验/写回单次原子执行，并发上报不丢更新；
        // 前置状态（INITIATED/COLLECTING）与 deviceCode 匹配校验在脚本内完成
        // AD-006：脚本经 runner 执行（生产=Redis Lua 原子，测试=内存模拟）
        String result = scriptRunner.execute(REPORT_PHASE_SCRIPT, key(taskId),
                phase, deviceCode == null ? "" : deviceCode,
                Instant.now().toString(), String.valueOf(TASK_TTL_MINUTES));
        if (result == null) {
            return null; // 任务不存在
        }
        if ("MISMATCH".equals(result)) {
            throw new IllegalArgumentException("设备码与任务不匹配");
        }
        return read(result);
    }

    /**
     * 标记任务完成（AC-84-14）：enroll 落库成功后调用（VoiceprintController.enroll 携带 taskId 时）；
     * 任务不存在静默忽略。
     */
    public void complete(String taskId) {
        complete(taskId, null);
    }

    /**
     * 标记任务完成（AC-84-14）：enroll 落库成功后调用；任务不存在静默忽略。
     * AD-006：Lua 原子写；deviceCode 提供时校验与任务归属一致（管理端调用可传 null 跳过）。
     */
    public void complete(String taskId, String deviceCode) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        applyTerminalPhase(taskId, PHASE_COMPLETED, deviceCode);
    }

    /** 标记任务失败（可重试：客户端新建任务） */
    public void fail(String taskId) {
        fail(taskId, null);
    }

    /**
     * 标记任务失败（可重试：客户端新建任务）。
     * AD-006：Lua 原子写；deviceCode 提供时校验与任务归属一致。
     */
    public void fail(String taskId, String deviceCode) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        applyTerminalPhase(taskId, PHASE_FAILED, deviceCode);
    }

    /** 终态原子写（AD-006）：任务存在 + 归属一致（脚本内校验，失败不写回）后单次脚本置位 */
    private void applyTerminalPhase(String taskId, String phase, String deviceCode) {
        String result = scriptRunner.execute(TERMINAL_PHASE_SCRIPT, key(taskId),
                phase, Instant.now().toString(), String.valueOf(TASK_TTL_MINUTES),
                deviceCode == null ? "" : deviceCode);
        if (result == null) {
            return; // 任务不存在
        }
        if ("MISMATCH".equals(result)) {
            throw new IllegalArgumentException("设备码与任务不匹配");
        }
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
