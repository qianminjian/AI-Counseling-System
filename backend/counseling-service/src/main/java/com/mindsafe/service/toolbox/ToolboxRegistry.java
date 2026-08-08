package com.mindsafe.service.toolbox;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 心理工具箱注册表（TOOL-001，design/36 §3.2 统一工具框架）
 * <p>
 * 开闭原则：新工具只需添加 ToolDefinition，零框架改动。
 * 每个工具 = 声明式配置（toolId/title/duration/steps/pre-postMoodCheck）。
 * <p>
 * 内置 5 个工具（design/36 §3.1）：
 * <ul>
 *   <li>深呼吸（已有 RelaxationExercises 基础）</li>
 *   <li>安静小青蛙（正念身体扫描）</li>
 *   <li>找一找（54321 接地法）</li>
 *   <li>心情温度计（情绪外化）</li>
 *   <li>我的安全小岛（安全计划儿童版）</li>
 * </ul>
 */
@Component
public class ToolboxRegistry {

    /** 工具定义 */
    public record ToolDefinition(
            String toolId,
            String title,
            String emoji,
            int durationSec,
            int minGrade,
            boolean preMoodCheck,
            boolean postMoodCheck,
            String rewardBadge,
            ToolCategory category
    ) {
    }

    /** 工具分类 */
    public enum ToolCategory {
        BREATHING,      // 呼吸放松
        MINDFULNESS,    // 正念
        GROUNDING,      // 接地
        EMOTION_CHECK,  // 情绪检测
        SAFETY_PLAN     // 安全计划
    }

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolboxRegistry() {
        register(new ToolDefinition("breathing_box", "深呼吸", "🫧",
                150, 1, true, true, "breathing_star", ToolCategory.BREATHING));
        register(new ToolDefinition("mindful_frog", "安静小青蛙", "🐸",
                180, 2, true, true, "mindful_frog", ToolCategory.MINDFULNESS));
        register(new ToolDefinition("grounding_54321", "找一找", "🔍",
                180, 1, true, true, null, ToolCategory.GROUNDING));
        register(new ToolDefinition("mood_thermometer", "心情温度计", "🌡️",
                60, 1, false, false, null, ToolCategory.EMOTION_CHECK));
        register(new ToolDefinition("safe_island", "我的安全小岛", "🏝️",
                300, 2, false, false, "island_builder", ToolCategory.SAFETY_PLAN));
    }

    public void register(ToolDefinition tool) {
        tools.put(tool.toolId(), tool);
    }

    /**
     * 获取所有工具（按注册顺序）。
     */
    public List<ToolDefinition> listAll() {
        return List.copyOf(tools.values());
    }

    /**
     * 按年级过滤可用工具。
     */
    public List<ToolDefinition> listForGrade(int grade) {
        return tools.values().stream()
                .filter(t -> grade >= t.minGrade())
                .toList();
    }

    /**
     * 按 ID 获取工具。
     */
    public Optional<ToolDefinition> getById(String toolId) {
        return Optional.ofNullable(tools.get(toolId));
    }

    /**
     * 按分类获取工具。
     */
    public List<ToolDefinition> listByCategory(ToolCategory category) {
        return tools.values().stream()
                .filter(t -> t.category() == category)
                .toList();
    }

    /**
     * 获取 SOS 可用工具（接地 + 呼吸，离线可打开）。
     */
    public List<ToolDefinition> listSosTools() {
        return tools.values().stream()
                .filter(t -> t.category() == ToolCategory.GROUNDING
                        || t.category() == ToolCategory.BREATHING)
                .toList();
    }
}
