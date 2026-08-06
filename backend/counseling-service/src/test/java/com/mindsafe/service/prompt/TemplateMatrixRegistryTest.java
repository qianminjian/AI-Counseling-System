package com.mindsafe.service.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TemplateMatrixRegistry 单测（G-1，design/45 §4.2 模板矩阵 + §7.1 红队护栏 + §6.1 门禁）
 * <p>
 * 契约：
 * - 矩阵覆盖 design/45 §4.2 全部 14 个模板 key
 * - 版本命名规范 {KEY}_{lang}_v{semver} 校验
 * - 三门禁：红队通过 + 审校签字 + eval 不回退
 * - 护栏用例集覆盖 design/45 §7.1 六类攻击面
 */
class TemplateMatrixRegistryTest {

    private final TemplateMatrixRegistry registry = new TemplateMatrixRegistry();

    // ===== 模板矩阵 =====

    @Test
    void 矩阵覆盖design45全部14个模板() {
        Set<String> keys = registry.getMatrix().stream()
                .map(TemplateMatrixRegistry.TemplateEntry::templateId)
                .collect(Collectors.toSet());

        Set<String> expected = Set.of(
                "SYS_001", "SAF_001", "SAF_002",
                "LANG_001", "LANG_002", "LANG_003",
                "EMO_001", "SKL_001", "SKL_002", "SKL_003",
                "TSK_001", "TSK_002", "TSK_003", "TSK_004");
        assertEquals(expected, keys);
    }

    @Test
    void 矩阵条目版本命名全部符合规范() {
        for (TemplateMatrixRegistry.TemplateEntry e : registry.getMatrix()) {
            assertTrue(registry.isValidVersion(e.version()),
                    "版本命名不合规: " + e.templateId() + " → " + e.version());
        }
    }

    @Test
    void findActive返回生效条目_未知返回null() {
        assertNotNull(registry.findActive("SYS_001"));
        assertNull(registry.findActive("NOT-EXIST"));
    }

    // ===== 版本命名规范 =====

    @Test
    void 版本命名规范_正反例() {
        assertTrue(registry.isValidVersion("SYS_001_zh-CN_v1.0.0"));
        assertTrue(registry.isValidVersion("EMO_001_zh-CN_v1.2.3"));
        assertFalse(registry.isValidVersion("sys_001_zh-CN_v1.0.0"));   // 小写 key
        assertFalse(registry.isValidVersion("SYS_001_v1.0.0"));          // 缺语言段
        assertFalse(registry.isValidVersion("SYS_001_zh-CN_v1.0"));      // semver 不完整
        assertFalse(registry.isValidVersion("SYS-001_zh-CN_v1.0.0"));    // 连字符 key 不再合法（ARCH-010 D4）
        assertFalse(registry.isValidVersion(null));
        assertFalse(registry.isValidVersion(""));
    }

    // ===== 三门禁 =====

    @Test
    void 三门禁全过() {
        TemplateMatrixRegistry.GateResult r = registry.checkReleaseGate(true, "临床张老师", 0.82, 0.80);
        assertTrue(r.passed());
        assertTrue(r.failures().isEmpty());
    }

    @Test
    void 红队未过_门禁拒绝() {
        TemplateMatrixRegistry.GateResult r = registry.checkReleaseGate(false, "临床张老师", 0.82, 0.80);
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("红队")));
    }

    @Test
    void 审校未签字_门禁拒绝() {
        TemplateMatrixRegistry.GateResult r = registry.checkReleaseGate(true, null, 0.82, 0.80);
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("审校")));
    }

    @Test
    void eval回退_门禁拒绝() {
        TemplateMatrixRegistry.GateResult r = registry.checkReleaseGate(true, "临床张老师", 0.75, 0.80);
        assertFalse(r.passed());
        assertTrue(r.failures().stream().anyMatch(f -> f.contains("eval")));
    }

    @Test
    void 三项同时失败_全部列出() {
        TemplateMatrixRegistry.GateResult r = registry.checkReleaseGate(false, "", 0.5, 0.8);
        assertEquals(3, r.failures().size());
    }

    // ===== 红队护栏用例集（design/45 §7.1 六类攻击面） =====

    @Test
    void 护栏用例集覆盖六类攻击面() {
        Set<String> categories = registry.getGuardrailCases().stream()
                .map(TemplateMatrixRegistry.GuardrailCase::category)
                .collect(Collectors.toSet());

        // design/45 §7.1：角色泄露 / 越狱绕过 / 诱导自伤细节 / 隐私套取 / 注入攻击 / 情绪操纵
        assertTrue(categories.contains("role_leakage"), "缺角色泄露类");
        assertTrue(categories.contains("jailbreak"), "缺越狱绕过类");
        assertTrue(categories.contains("self_harm"), "缺诱导自伤细节类");
        assertTrue(categories.contains("privacy_elicitation"), "缺隐私套取类");
        assertTrue(categories.contains("injection"), "缺注入攻击类");
        assertTrue(categories.contains("emotional_manipulation"), "缺情绪操纵类");
    }

    @Test
    void 护栏用例集包含正常对话正例_防止过度拦截() {
        List<TemplateMatrixRegistry.GuardrailCase> passCases = registry.getGuardrailCasesByCategory("normal");
        assertFalse(passCases.isEmpty(), "必须保留 PASS 正例，防止护栏过度拦截正常对话");
    }

    @Test
    void 按类别过滤护栏用例() {
        List<TemplateMatrixRegistry.GuardrailCase> jailbreak = registry.getGuardrailCasesByCategory("jailbreak");
        assertFalse(jailbreak.isEmpty());
        assertTrue(jailbreak.stream().allMatch(c -> "jailbreak".equals(c.category())));
    }
}
