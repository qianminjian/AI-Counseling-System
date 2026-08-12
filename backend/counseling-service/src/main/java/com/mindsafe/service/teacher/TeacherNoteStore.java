package com.mindsafe.service.teacher;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 教师备注（teacher_notes）读写单点（S-007①，doing/93）。
 * <p>
 * TeacherService 上帝类六子域中最先拆出的子域：全部 11 处 teacherNoteMapper 直接操作
 * 收敛于此（插入/最新一条/按学生取最新/类型列表/全量列表），加字段或换存储只改本组件。
 * 备注是跨子域共享的旁路数据（转派/跟踪标志/阶段推进/预警处理/回访/档案备注），
 * 独立成组件后各子域不再背负"记得过滤租户+学生+倒序"的隐性契约。
 */
@Component
public class TeacherNoteStore {

    private final TeacherNoteMapper teacherNoteMapper;

    public TeacherNoteStore(TeacherNoteMapper teacherNoteMapper) {
        this.teacherNoteMapper = teacherNoteMapper;
    }

    /** 插入一条备注（调用方负责构造与加密） */
    public void insert(TeacherNote note) {
        teacherNoteMapper.insert(note);
    }

    /** 某学生某类型的最新一条备注（空则 Optional.empty） */
    public Optional<TeacherNote> latest(UUID tenantId, UUID studentUserId, String noteType) {
        // AUD-043：分页插件安全化，替代 .last("LIMIT 1") 字符串拼接
        List<TeacherNote> notes = teacherNoteMapper.selectPage(
                new Page<>(1, 1, false),
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .eq(TeacherNote::getNoteType, noteType)
                        .orderByDesc(TeacherNote::getCreatedAt)
        ).getRecords();
        return notes.isEmpty() ? Optional.empty() : Optional.of(notes.get(0));
    }

    /** 租户内某类型备注按学生取最新（批量避免 N+1） */
    public Map<UUID, TeacherNote> latestByStudent(UUID tenantId, String noteType) {
        List<TeacherNote> notes = teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getNoteType, noteType)
        );
        Map<UUID, TeacherNote> latestByStudent = new HashMap<>();
        for (TeacherNote note : notes) {
            latestByStudent.merge(note.getStudentUserId(), note,
                    (a, b) -> a.getCreatedAt().isBefore(b.getCreatedAt()) ? b : a);
        }
        return latestByStudent;
    }

    /** 某学生某类型的备注列表（按创建时间倒序） */
    public List<TeacherNote> list(UUID tenantId, UUID studentUserId, String noteType) {
        return teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .eq(TeacherNote::getNoteType, noteType)
                        .orderByDesc(TeacherNote::getCreatedAt)
        );
    }

    /** 某学生的全部备注（按创建时间倒序，档案展示用） */
    public List<TeacherNote> listAll(UUID tenantId, UUID studentUserId) {
        return teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .orderByDesc(TeacherNote::getCreatedAt)
        );
    }

    /** 某学生的全部备注 ID 集合（去重；误报纠错/删除联动用） */
    public Set<UUID> noteIdsOf(UUID tenantId, UUID studentUserId) {
        return teacherNoteMapper.selectList(
                new LambdaQueryWrapper<TeacherNote>()
                        .eq(TeacherNote::getTenantId, tenantId)
                        .eq(TeacherNote::getStudentUserId, studentUserId)
                        .select(TeacherNote::getNoteId)
        ).stream().map(TeacherNote::getNoteId).collect(Collectors.toSet());
    }
}
