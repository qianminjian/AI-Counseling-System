package com.mindsafe.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.mindsafe.ai.safety.PiiDesensitizer;

/**
 * 全局日志脱敏 Appender（AUDIT-P1-15）。
 * <p>
 * 在消息写入底层 appender 之前，复用 {@link PiiDesensitizer}（counseling-ai 模块）
 * 对手机号、身份证、邮箱、姓名、地址做掩码，防止敏感信息经日志外泄。
 * 对开发控制台（pattern）与生产 JSON（LogstashEncoder）两种 encoder 均生效，
 * 因为脱敏发生在事件层，encoder 只看到脱敏后的消息。
 * <p>
 * 注意：logback 在 Spring 容器初始化之前就开始工作，因此这里直接
 * {@code new PiiDesensitizer()}，不能依赖 {@code @Component} 注入。
 * <p>
 * 装配方式（logback-spring.xml）：在底层 appender 外包一层本类，通过
 * {@code <delegate>} 嵌套注入底层 appender。
 */
public class PiiDesensitizingAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private final PiiDesensitizer desensitizer = new PiiDesensitizer();

    /** 底层真实写入的 appender，由 logback Joran 通过 &lt;delegate&gt; 注入 */
    private Appender<ILoggingEvent> delegate;

    public void setDelegate(Appender<ILoggingEvent> delegate) {
        this.delegate = delegate;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (delegate == null) {
            addError("delegate appender 未配置，日志被丢弃");
            return;
        }
        ILoggingEvent toWrite = event;
        String raw = event.getFormattedMessage();
        if (raw != null && !raw.isEmpty()) {
            String masked = desensitizer.desensitize(raw);
            if (masked != null && !masked.equals(raw)) {
                toWrite = new MaskedLoggingEvent(event, masked);
            }
        }
        delegate.doAppend(toWrite);
    }

    @Override
    public void start() {
        if (delegate == null) {
            addError("delegate appender 未配置，拒绝启动");
            return;
        }
        if (!delegate.isStarted()) {
            delegate.start();
        }
        super.start();
    }

    @Override
    public void stop() {
        if (delegate != null && delegate.isStarted()) {
            delegate.stop();
        }
        super.stop();
    }
}
