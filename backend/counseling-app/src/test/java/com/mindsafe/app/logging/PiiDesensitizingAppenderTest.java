package com.mindsafe.app.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUDIT-P1-15：logback 全局日志脱敏 Appender 测试。
 * <p>
 * 以 ListAppender 作为底层 delegate，验证写入的消息已脱敏。
 */
class PiiDesensitizingAppenderTest {

    private final PiiDesensitizingAppender appender = new PiiDesensitizingAppender();
    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        listAppender.setName("test-list");
        listAppender.setContext(new ch.qos.logback.classic.LoggerContext());
        appender.setName("test-pii");
        appender.setContext(listAppender.getContext());
        appender.setDelegate(listAppender);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        appender.stop();
    }

    private ILoggingEvent event(String message) {
        Logger logger = (Logger) LoggerFactory.getLogger("PiiDesensitizingAppenderTest");
        return new LoggingEvent("test", logger, Level.INFO, message, null, new Object[0]);
    }

    @Test
    @DisplayName("手机号日志被掩码：13812345678 → 138****78")
    void masksPhone() {
        appender.doAppend(event("学生家长手机号 13812345678 已登记"));
        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .isEqualTo("学生家长手机号 138****78 已登记");
    }

    @Test
    @DisplayName("身份证号日志被掩码")
    void masksIdCard() {
        appender.doAppend(event("证件号 110101199003074512 已校验"));
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .isEqualTo("证件号 110*************12 已校验")
                .doesNotContain("110101199003074512");
    }

    @Test
    @DisplayName("邮箱日志被掩码：test@example.com → t***@example.com")
    void masksEmail() {
        appender.doAppend(event("联系邮箱 test@example.com"));
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .isEqualTo("联系邮箱 t***@example.com");
    }

    @Test
    @DisplayName("姓名（上下文句式）被掩码：我叫王小明 → 我叫某人")
    void masksName() {
        appender.doAppend(event("我叫王小明，我今年10岁"));
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .isEqualTo("我叫某人，我今年10岁");
    }

    @Test
    @DisplayName("地址被掩码：北京市海淀区中关村大街1号 → 不含具体地址")
    void masksAddress() {
        appender.doAppend(event("住址：北京市海淀区中关村大街1号"));
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .doesNotContain("中关村大街");
    }

    @Test
    @DisplayName("无 PII 日志原样透传")
    void passesThroughCleanMessage() {
        appender.doAppend(event("普通业务日志，无敏感信息"));
        assertThat(listAppender.list.get(0).getFormattedMessage())
                .isEqualTo("普通业务日志，无敏感信息");
    }

    @Test
    @DisplayName("空消息透传不脱敏")
    void passesThroughEmptyMessage() {
        appender.doAppend(event(""));
        assertThat(listAppender.list.get(0).getFormattedMessage()).isEmpty();
    }

    @Test
    @DisplayName("未配置 delegate 时拒绝启动")
    void refusesStartWithoutDelegate() {
        PiiDesensitizingAppender noDelegate = new PiiDesensitizingAppender();
        noDelegate.setName("no-delegate");
        noDelegate.setContext(listAppender.getContext());
        noDelegate.start();
        assertThat(noDelegate.isStarted()).isFalse();
    }

    @Test
    @DisplayName("addAppender 装配（AppenderAttachable，logback-spring.xml <appender-ref> 路径）可脱敏")
    void worksWithAppenderAttachableAssembly() {
        PiiDesensitizingAppender attached = new PiiDesensitizingAppender();
        attached.setName("attached-pii");
        attached.setContext(listAppender.getContext());
        attached.addAppender(listAppender);
        attached.start();
        try {
            assertThat(attached.isStarted()).isTrue();
            attached.doAppend(event("手机号 13900001111 已登记"));
            assertThat(listAppender.list).hasSize(1);
            assertThat(listAppender.list.get(0).getFormattedMessage())
                    .isEqualTo("手机号 139****11 已登记");
        } finally {
            attached.stop();
        }
    }
}
