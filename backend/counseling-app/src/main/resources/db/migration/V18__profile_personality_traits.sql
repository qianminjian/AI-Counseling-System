-- V18: 学生画像新增性格特征列（PROF-016）
-- 存储 LLM 提炼的性格维度：introversion/sensitivity/curiosity/dominant_interests

ALTER TABLE tenant_template.student_profiles
  ADD COLUMN IF NOT EXISTS personality_traits JSONB NOT NULL DEFAULT '{}';

COMMENT ON COLUMN tenant_template.student_profiles.personality_traits
  IS '性格特征（LLM 提炼）：introversion/sensitivity/curiosity/dominant_interests';
