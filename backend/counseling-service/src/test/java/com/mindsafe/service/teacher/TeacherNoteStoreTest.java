package com.mindsafe.service.teacher;

import com.mindsafe.domain.entity.TeacherNote;
import com.mindsafe.domain.mapper.TeacherNoteMapper;
import com.mindsafe.service.security.FieldEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeacherNoteStore 解密读单点单测（P2-4：5 处直接 decrypt 收敛后，行为与调用方直解一致）。
 */
class TeacherNoteStoreTest {

    @Test
    @DisplayName("decryptContent：委托 FieldEncryptionService 解密")
    void decryptContent_delegates() {
        FieldEncryptionService fes = mock(FieldEncryptionService.class);
        when(fes.decrypt("enc:abc")).thenReturn("明文");
        TeacherNoteStore store = new TeacherNoteStore(mock(TeacherNoteMapper.class), fes);

        TeacherNote note = new TeacherNote();
        note.setContent("enc:abc");

        assertThat(store.decryptContent(note)).isEqualTo("明文");
        verify(fes).decrypt("enc:abc");
    }

    @Test
    @DisplayName("decryptContent：未启用加密时明文透传（存量数据兜底）")
    void decryptContent_plainPassthrough() {
        FieldEncryptionService fes = new FieldEncryptionService(false, "", 1, "",
                new StandardEnvironment());
        TeacherNoteStore store = new TeacherNoteStore(mock(TeacherNoteMapper.class), fes);

        TeacherNote note = new TeacherNote();
        note.setContent("普通备注");

        assertThat(store.decryptContent(note)).isEqualTo("普通备注");
    }
}
