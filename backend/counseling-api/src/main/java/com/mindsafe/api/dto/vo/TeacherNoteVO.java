package com.mindsafe.api.dto.vo;

import com.mindsafe.domain.entity.TeacherNote;

import java.time.Instant;
import java.util.UUID;

/**
 * 教师备注 VO（F9：addNote 响应，替代实体直接暴露）。
 * <p>
 * 字段语义与 {@link TeacherNote} 一致（仅包装层变化，契约不变）。
 */
public record TeacherNoteVO(
        UUID noteId,
        UUID tenantId,
        UUID studentUserId,
        UUID teacherUserId,
        String content,
        String noteType,
        Instant createdAt
) {
    public static TeacherNoteVO from(TeacherNote n) {
        return new TeacherNoteVO(n.getNoteId(), n.getTenantId(), n.getStudentUserId(), n.getTeacherUserId(),
                n.getContent(), n.getNoteType(), n.getCreatedAt());
    }
}
