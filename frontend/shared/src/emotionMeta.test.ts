import { describe, it, expect } from 'vitest';
import { EMOTION_META, emotionLabel, emotionEmoji } from './emotionMeta';

/**
 * 情绪元数据单一源测试（F4 收编，doing/78 §F4）
 * - 覆盖后端 EmotionVocabulary 权威码值全集（ZH_LABELS 17 + POSITIVE_KEYS 补充 2）
 * - emotionLabel 对齐后端 labelOf 语义（unknown 原样 / 空串 / DC-008 单译）
 * - emotionEmoji 按码值查询与中文标签兼容
 */

/** 后端 EmotionVocabulary 权威码值全集（ZH_LABELS 17 + POSITIVE_KEYS 的 relieved/hopeful） */
const BACKEND_AUTHORITATIVE_CODES = [
  'happy', 'sad', 'angry', 'scared', 'fearful', 'nervous', 'anxious',
  'neutral', 'calm', 'excited', 'surprised', 'disgusted', 'tired',
  'withdrawn', 'lonely', 'crisis', 'relieved', 'hopeful',
];

describe('emotionMeta 情绪元数据单一源', () => {
  it('覆盖后端 EmotionVocabulary 权威码值全集且唯一', () => {
    const codes = EMOTION_META.map(m => m.code);
    expect(new Set(codes).size).toBe(codes.length); // 无重复
    BACKEND_AUTHORITATIVE_CODES.forEach(c => expect(codes).toContain(c));
  });

  it('emotionLabel 对齐后端 labelOf：码值转中文、未知原样返回、空返回空串', () => {
    expect(emotionLabel('happy')).toBe('开心');
    expect(emotionLabel('sad')).toBe('难过');
    expect(emotionLabel('anxious')).toBe('紧张'); // DC-008：anxious 全系统单译
    expect(emotionLabel('fearful')).toBe('恐惧');
    expect(emotionLabel('unknown_code')).toBe('unknown_code');
    expect(emotionLabel('')).toBe('');
  });

  it('emotionEmoji 按码值查询', () => {
    expect(emotionEmoji('happy')).toBe('😊');
    expect(emotionEmoji('sad')).toBe('😢');
    expect(emotionEmoji('angry')).toBe('😠');
    expect(emotionEmoji('unknown')).toBe('');
    expect(emotionEmoji('')).toBe('');
  });

  it('emotionEmoji 兼容中文标签入参（家长端周报场景）', () => {
    expect(emotionEmoji('难过')).toBe('😢');
    expect(emotionEmoji('开心')).toBe('😊');
    // '平静' 为 neutral/calm 共同标签：确定性命中先出现的 neutral；calm 请用码值查询
    expect(emotionEmoji('平静')).toBe('😐');
    expect(emotionEmoji('calm')).toBe('😌');
    expect(emotionEmoji('不存在的情绪')).toBe('');
  });

  it('未知码值原样返回、无 emoji（MessageBubble 🎵 兜底语义不变）', () => {
    const unknown = EMOTION_META.find(m => m.code === 'unknown');
    expect(unknown).toBeDefined();
    expect(unknown!.label).toBe('');
    expect(unknown!.emoji).toBe('');
  });
});
