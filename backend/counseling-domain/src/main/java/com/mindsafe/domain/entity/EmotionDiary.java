package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 情绪日记实体（对应 tenant_template.emotion_diaries）
 */
@TableName(value = "emotion_diaries", schema = "tenant_template")
public class EmotionDiary {

    @TableId(value = "diary_id", type = IdType.INPUT)
    private UUID diaryId;

    private UUID tenantId;
    private UUID studentUserId;
    private String emotionLabel;
    private Integer intensity;
    private String note;
    private LocalDate diaryDate;
    private Instant createdAt;

    public static EmotionDiary create(UUID tenantId, UUID studentUserId,
                                      String emotionLabel, int intensity, String note) {
        EmotionDiary d = new EmotionDiary();
        d.diaryId = UUID.randomUUID();
        d.tenantId = tenantId;
        d.studentUserId = studentUserId;
        d.emotionLabel = emotionLabel;
        d.intensity = intensity;
        d.note = note;
        d.diaryDate = LocalDate.now();
        d.createdAt = Instant.now();
        return d;
    }

    // ===== Getters & Setters =====
    public UUID getDiaryId() { return diaryId; }
    public void setDiaryId(UUID diaryId) { this.diaryId = diaryId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public String getEmotionLabel() { return emotionLabel; }
    public void setEmotionLabel(String emotionLabel) { this.emotionLabel = emotionLabel; }

    public Integer getIntensity() { return intensity; }
    public void setIntensity(Integer intensity) { this.intensity = intensity; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDate getDiaryDate() { return diaryDate; }
    public void setDiaryDate(LocalDate diaryDate) { this.diaryDate = diaryDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
