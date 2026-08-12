package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.EmotionDiary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmotionDiaryMapper extends BaseMapper<EmotionDiary> {

    /**
     * 打卡原子 upsert（doing/92 R-011：原 selectOne+insert 两步非原子，并发双击可双落；
     * 唯一索引 uq_diary_student_date 冲突时覆盖更新）。
     * <p>
     * tenantLine 显式跳过：SQL 已显式携带 tenant_id 列（行隔离由 service 参数保证，
     * 与拦截器注入同源同值）；避免租户插件解析 ON CONFLICT 子句的不确定性。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO tenant_template.emotion_diaries
                (diary_id, tenant_id, student_user_id, emotion_label, intensity, note, diary_date, created_at)
            VALUES (#{diaryId}, #{tenantId}, #{studentUserId}, #{emotionLabel}, #{intensity}, #{note}, #{diaryDate}, #{createdAt})
            ON CONFLICT (tenant_id, student_user_id, diary_date)
            DO UPDATE SET emotion_label = EXCLUDED.emotion_label,
                          intensity = EXCLUDED.intensity,
                          note = EXCLUDED.note
            """)
    int upsertCheckin(EmotionDiary diary);
}
