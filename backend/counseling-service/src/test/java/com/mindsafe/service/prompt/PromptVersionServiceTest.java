package com.mindsafe.service.prompt;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.domain.mapper.PromptVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptVersionService 单元测试（AI-005 A/B 路由逻辑）
 */
@ExtendWith(MockitoExtension.class)
class PromptVersionServiceTest {

    @Mock
    private PromptVersionMapper promptVersionMapper;

    @Mock
    private PromptTemplateService promptTemplateService;

    private PromptVersionService service;

    @BeforeEach
    void setUp() {
        service = new PromptVersionService(promptVersionMapper, promptTemplateService);
    }

    @Test
    @DisplayName("A/B 分组：hash % 10 < 2 → treatment_a（约 20%）")
    void assignAbGroup_distribution() {
        int treatmentCount = 0;
        int total = 1000;

        for (int i = 0; i < total; i++) {
            UUID userId = UUID.randomUUID();
            String group = service.assignAbGroup(userId);
            assertTrue(group.equals("control") || group.equals("treatment_a"));
            if ("treatment_a".equals(group)) treatmentCount++;
        }

        // 统计验证：treatment_a 比例应在 10%-30% 之间（期望 20%）
        double ratio = (double) treatmentCount / total;
        assertTrue(ratio > 0.10 && ratio < 0.30,
                "treatment_a 比例异常: " + ratio + " (期望约 0.2)");
    }

    @Test
    @DisplayName("A/B 分组：同一用户始终在同一组（确定性）")
    void assignAbGroup_deterministic() {
        UUID userId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        String group1 = service.assignAbGroup(userId);
        String group2 = service.assignAbGroup(userId);
        assertEquals(group1, group2);
    }

    @Test
    @DisplayName("缓存失效不抛异常")
    void invalidateCache_noException() {
        assertDoesNotThrow(() -> service.invalidateCache());
    }
}
