package com.mindsafe.service.voice;

/**
 * 音色渲染档案（TMATCH-001，design/48 §4.2）
 * <p>
 * 由 {@link VoicePersonaResolver#resolve} 按"手动偏好 > 场景锁定 > 画像冷启动 > 情绪基调"
 * 裁决计算得出，作为 tts-service 合成请求的完整参数集。
 *
 * @param persona         音色人设（xiaoxing/qiqiu/yueliang/xiaotaiyang）
 * @param emotionInstruct 情绪 instruct 标签（happy/sad/angry/fearful/nervous/neutral）
 * @param pitchScale      音高基调（情绪+年龄，1.0=自然，ACTIVATED 态更低）
 * @param speed           语速基调倍率（情绪+年龄，与前端语速参数相乘）
 * @param pauseStyle      停顿风格（0=轻快 1=自然 2=多停顿安抚）
 * @param locked          安全/危机场景锁定稳定基调（情绪 instruct 不得干扰，design/48 §6.3 红线）
 * @param source          persona 决策来源（default/profile/manual）
 */
public record VoiceRenderProfile(
        String persona,
        String emotionInstruct,
        double pitchScale,
        double speed,
        int pauseStyle,
        boolean locked,
        String source
) {
}
