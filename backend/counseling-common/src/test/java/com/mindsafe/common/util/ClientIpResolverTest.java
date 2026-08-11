package com.mindsafe.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClientIpResolver 单元测试（doing/90 P-001，AC-90-03）
 * 覆盖：单级/多级/尾随逗号/空段/无 XFF 回退。
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("单级 XFF：返回唯一条目")
    void singleEntry() {
        assertThat(ClientIpResolver.parseClientIp("1.2.3.4", "9.9.9.9"))
                .isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("多级 XFF：返回最右条目（代理追加的真实 IP，不可伪造）")
    void multiEntryTakesRightmost() {
        // 攻击者伪造前缀 6.6.6.6，代理追加真实 1.2.3.4 → 取 1.2.3.4
        assertThat(ClientIpResolver.parseClientIp("6.6.6.6, 1.2.3.4", "9.9.9.9"))
                .isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("尾随逗号/重复逗号脏数据：跳过空段取最右非空")
    void dirtyEntriesSkipsEmpty() {
        assertThat(ClientIpResolver.parseClientIp("1.2.3.4,, ,5.6.7.8,", "9.9.9.9"))
                .isEqualTo("5.6.7.8");
    }

    @Test
    @DisplayName("无 XFF / 空 XFF：返回 fallback")
    void missingXffUsesFallback() {
        assertThat(ClientIpResolver.parseClientIp(null, "9.9.9.9")).isEqualTo("9.9.9.9");
        assertThat(ClientIpResolver.parseClientIp("  ", "9.9.9.9")).isEqualTo("9.9.9.9");
    }
}
