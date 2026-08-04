/**
 * 工具练习结构化内容包（design/36 §3.2 统一工具框架）
 *
 * 每个工具 = 声明式配置（后端 ToolboxRegistry）+ 步骤内容包（本文件）。
 * 内容包为前端静态模块，随构建打包 → CacheFirst 天然离线可用（§4.1）。
 *
 * 开闭原则：新工具只需在 TOOL_STEPS 增加条目，ToolPractice 框架零改动。
 *
 * 当前形态：narration 分步引导文案 + 每步建议时长；
 * 音频（tts-service 构建期产物）与 boboAnim（Lottie）为后续迭代字段，
 * 类型已预留，缺失时框架按纯文字降级（§3.2）。
 */

export interface ToolStep {
  /** 引导文案（波波口吻，低年级可读） */
  text: string
  /** 本步建议停留秒数（倒计时内自动推进，也可手动"下一步"） */
  durationSec: number
  /** 预留：tts-service 构建期生成的音频文件名 */
  audio?: string
  /** 预留：波波 Lottie 动画名 */
  boboAnim?: string
}

/**
 * 内容包注册表：toolId → 步骤列表。
 * 与后端 ToolboxRegistry 的 5 个内置工具一一对应。
 */
export const TOOL_STEPS: Record<string, ToolStep[]> = {
  breathing_box: [
    { text: '找一个舒服的姿势坐好，把小手轻轻放在肚子上 🫧', durationSec: 20 },
    { text: '用鼻子慢慢吸气……数 4 下，感觉肚子像气球一样鼓起来', durationSec: 30 },
    { text: '屏住一下下……数 2 下', durationSec: 20 },
    { text: '再用嘴巴慢慢呼气……数 6 下，感觉气球慢慢变扁', durationSec: 30 },
    { text: '真棒！我们再来一轮：吸气……呼气……', durationSec: 40 },
    { text: '最后深深吸一口气，慢慢吐出来，感觉身体变轻了 ✨', durationSec: 10 },
  ],
  mindful_frog: [
    { text: '想象你是一只安静的小青蛙，坐在一片大大的荷叶上 🐸', durationSec: 25 },
    { text: '先动动你的脚趾头……感觉它们暖暖的', durationSec: 25 },
    { text: '现在感觉你的小腿和膝盖……放松下来', durationSec: 25 },
    { text: '感觉你的小肚子，随着呼吸轻轻起伏', durationSec: 25 },
    { text: '感觉你的肩膀……让它们垂下来，不用力气', durationSec: 25 },
    { text: '最后感觉你的脸蛋和头顶……全身都安静下来了 🌙', durationSec: 55 },
  ],
  grounding_54321: [
    { text: '我们来玩找一找的游戏！先看一看四周 🔍', durationSec: 15 },
    { text: '说出 5 个你看到的东西（比如：桌子、云朵……）', durationSec: 40 },
    { text: '再摸一摸 4 个你能碰到的东西，感觉它们是软的还是凉的', durationSec: 35 },
    { text: '竖起耳朵，听 3 个你能听到的声音', durationSec: 30 },
    { text: '闻一闻，找 2 个你能闻到的气味', durationSec: 30 },
    { text: '最后找 1 件让你觉得开心的小事 💙', durationSec: 30 },
  ],
  mood_thermometer: [
    { text: '如果心情有温度，现在是几度呢？🌡️', durationSec: 20 },
    { text: '闭上眼睛感受一下：心里是暖暖的，还是有点凉凉的？', durationSec: 20 },
    { text: '给现在的心情起个小名字吧（比如"小火苗"、"小雨滴"）', durationSec: 20 },
  ],
  safe_island: [
    { text: '闭上眼睛，想象一座属于你的小岛 🏝️', durationSec: 40 },
    { text: '岛上有让你安心的画面：是沙滩、大树，还是小房子？', durationSec: 60 },
    { text: '在这座岛上，你可以做哪些让自己舒服的事？抱枕头、画画、听歌……', durationSec: 80 },
    { text: '当你难过的时候，谁可以陪你？想一想这些温暖的人 💙', durationSec: 60 },
    { text: '记住这座小岛，它永远在心里等着你回来 ✨', durationSec: 60 },
  ],
}

/** 获取指定工具的步骤内容包；未配置返回 null（框架降级为纯倒计时） */
export function getToolSteps(toolId: string): ToolStep[] | null {
  return TOOL_STEPS[toolId] ?? null
}
