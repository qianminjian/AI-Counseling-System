package com.mindsafe.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 教师备注实体（对应 tenant_template.teacher_notes）
 */
@TableName(value = "teacher_notes", schema = "tenant_template")
public class TeacherNote {

    @TableId(value = "note_id", type = IdType.INPUT)
    private UUID noteId;

    private UUID tenantId;
    private UUID studentUserId;
    private UUID teacherUserId;
    private String content;
    private String noteType;
    private Instant createdAt;

    public TeacherNote() {}

    public static TeacherNote create(UUID tenantId, UUID studentUserId, UUID teacherUserId,
                                     String content, String noteType) {
        TeacherNote note = new TeacherNote();
        note.noteId = UUID.randomUUID();
        note.tenantId = tenantId;
        note.studentUserId = studentUserId;
        note.teacherUserId = teacherUserId;
        note.content = content;
        note.noteType = noteType != null ? noteType : "general";
        note.createdAt = Instant.now();
        return note;
    }

    // ===== Getters & Setters =====
    public UUID getNoteId() { return noteId; }
    public void setNoteId(UUID noteId) { this.noteId = noteId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getStudentUserId() { return studentUserId; }
    public void setStudentUserId(UUID studentUserId) { this.studentUserId = studentUserId; }

    public UUID getTeacherUserId() { return teacherUserId; }
    public void setTeacherUserId(UUID teacherUserId) { this.teacherUserId = teacherUserId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getNoteType() { return noteType; }
    public void setNoteType(String noteType) { this.noteType = noteType; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
