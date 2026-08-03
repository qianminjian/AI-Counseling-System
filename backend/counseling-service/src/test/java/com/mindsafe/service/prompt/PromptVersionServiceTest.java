package com.mindsafe.service.prompt;

import com.mindsafe.ai.prompt.PromptTemplateService;
import com.mindsafe.domain.entity.PromptVersion;
import com.mindsafe.domain.mapper.PromptVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("A/B 分组：null 学生 ID → control")
    void assignAbGroup_null_returnsControl() {
        assertEquals("control", service.assignAbGroup(null));
    }

    @Nested
    @DisplayName("resolve：DB 优先 + classpath 降级 + 变量渲染")
    class Resolve {

        private final UUID tenantId = UUID.randomUUID();

        @Test
        @DisplayName("DB 命中 → 内容来自 DB，versionTag=KEY:vN:group，变量被渲染")
        void dbHit_rendersVariablesAndTagsFromDb() {
            PromptVersion pv = PromptVersion.create(tenantId, "SYS_001", 2,
                    "你好 {{name}}，我是波波", "测试版本", "control", null);
            when(promptVersionMapper.selectOne(any())).thenReturn(pv);

            PromptVersionService.ResolvedPrompt resolved =
                    service.resolve(tenantId, "SYS_001", null, Map.of("name", "小明"));

            assertEquals("你好 小明，我是波波", resolved.content());
            assertEquals("SYS_001:v2:control", resolved.versionTag());
            assertEquals("control", resolved.abGroup());
        }

        @Test
        @DisplayName("DB 未命中 → classpath 降级，versionTag=KEY:v0:classpath")
        void dbMiss_fallsBackToClasspath() {
            when(promptVersionMapper.selectOne(any())).thenReturn(null);
            when(promptTemplateService.getTemplate(PromptTemplateService.SYS_001))
                    .thenReturn("模板原文 {{grade}}");

            PromptVersionService.ResolvedPrompt resolved =
                    service.resolve(tenantId, "SYS_001", null, Map.of("grade", "3"));

            assertEquals("模板原文 3", resolved.content());
            assertEquals("SYS_001:v0:classpath", resolved.versionTag());
            verify(promptTemplateService).getTemplate(PromptTemplateService.SYS_001);
        }

        @Test
        @DisplayName("未知模板标识 + DB 未命中 → IllegalArgumentException")
        void unknownKey_throws() {
            when(promptVersionMapper.selectOne(any())).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> service.resolve(tenantId, "NOPE_999", null, Map.of()));
        }

        @Test
        @DisplayName("resolveRaw 不渲染变量（保留 {{占位符}} 原文）")
        void resolveRaw_noRendering() {
            PromptVersion pv = PromptVersion.create(tenantId, "LANG_001", 1,
                    "请对 {{grade}} 年级说话", null, "control", null);
            when(promptVersionMapper.selectOne(any())).thenReturn(pv);

            PromptVersionService.ResolvedPrompt resolved =
                    service.resolveRaw(tenantId, "LANG_001", null);

            assertTrue(resolved.content().contains("{{grade}}"));
        }

        @Test
        @DisplayName("缓存命中：二次 resolve 不再查 DB")
        void cacheHit_noDbQueryOnSecondResolve() {
            PromptVersion pv = PromptVersion.create(tenantId, "SYS_001", 1,
                    "内容", null, "control", null);
            when(promptVersionMapper.selectOne(any())).thenReturn(pv);

            service.resolve(tenantId, "SYS_001", null, Map.of());
            service.resolve(tenantId, "SYS_001", null, Map.of());

            // 首次：loadContent 查一次 + buildVersionTag 查一次；二次全命中缓存
            verify(promptVersionMapper, times(2)).selectOne(any());
        }
    }

    @Nested
    @DisplayName("版本管理（创建/激活/列表）")
    class Management {

        private final UUID tenantId = UUID.randomUUID();

        @Test
        @DisplayName("createVersion：无历史版本 → version=1，abGroup 缺省 control")
        void createFirstVersion() {
            when(promptVersionMapper.selectOne(any())).thenReturn(null);

            PromptVersion created = service.createVersion(tenantId, "SYS_001", "新内容", "首次", null, null);

            assertEquals(1, created.getVersion());
            assertEquals("control", created.getAbGroup());
            assertFalse(created.getIsActive());
            verify(promptVersionMapper).insert(created);
        }

        @Test
        @DisplayName("createVersion：已有 v3 → 新版本为 v4")
        void createNextVersion() {
            PromptVersion latest = PromptVersion.create(tenantId, "SYS_001", 3, "旧", null, "control", null);
            when(promptVersionMapper.selectOne(any())).thenReturn(latest);

            PromptVersion created = service.createVersion(tenantId, "SYS_001", "新内容", "迭代", "control", null);

            assertEquals(4, created.getVersion());
        }

        @Test
        @DisplayName("activateVersion：版本不存在 → IllegalArgumentException")
        void activateMissing_throws() {
            when(promptVersionMapper.selectById(any())).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> service.activateVersion(UUID.randomUUID()));
        }

        @Test
        @DisplayName("activateVersion：同组旧 active 版本被停用，目标被激活（互斥铁律）")
        void activate_deactivatesOthers() {
            UUID versionId = UUID.randomUUID();
            PromptVersion target = PromptVersion.create(tenantId, "SYS_001", 2, "新", null, "control", null);
            PromptVersion oldActive = PromptVersion.create(tenantId, "SYS_001", 1, "旧", null, "control", null);
            oldActive.setIsActive(true);
            when(promptVersionMapper.selectById(versionId)).thenReturn(target);
            when(promptVersionMapper.selectList(any())).thenReturn(List.of(oldActive));

            service.activateVersion(versionId);

            assertFalse(oldActive.getIsActive());
            assertTrue(target.getIsActive());
            // 旧版本停用 + 目标激活 = 2 次 updateById
            verify(promptVersionMapper, times(2)).updateById(any(PromptVersion.class));
        }

        @Test
        @DisplayName("activateVersion：全局级版本（tenantId=null）同样可激活")
        void activate_globalVersion() {
            UUID versionId = UUID.randomUUID();
            PromptVersion target = PromptVersion.create(null, "SAF_002", 1, "全局", null, "control", null);
            when(promptVersionMapper.selectById(versionId)).thenReturn(target);
            when(promptVersionMapper.selectList(any())).thenReturn(List.of());

            service.activateVersion(versionId);

            assertTrue(target.getIsActive());
            verify(promptVersionMapper).updateById(target);
        }

        @Test
        @DisplayName("listVersions：委托 mapper 返回版本列表")
        void listVersions_delegates() {
            PromptVersion pv = PromptVersion.create(tenantId, "SYS_001", 1, "内容", null, "control", null);
            when(promptVersionMapper.selectList(any())).thenReturn(List.of(pv));

            List<PromptVersion> versions = service.listVersions(tenantId, "SYS_001");

            assertEquals(1, versions.size());
        }
    }
}
