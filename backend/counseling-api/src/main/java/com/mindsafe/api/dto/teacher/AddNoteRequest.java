package com.mindsafe.api.dto.teacher;

/**
 * 教师备注新增请求（F11：addNote 请求体类型化，替代 Map&lt;String, String&gt;）。
 * <p>
 * 字段语义与原 Map 约定完全一致：noteType 缺省为 general。
 */
public record AddNoteRequest(String content, String noteType) {
    public AddNoteRequest {
        if (noteType == null) {
            noteType = "general";
        }
    }
}
