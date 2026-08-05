package com.mindsafe.app.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import com.mindsafe.ai.safety.PiiDesensitizer;

import java.util.Iterator;

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
 * 装配方式（logback-spring.xml）：底层 appender 独立声明，本类通过
 * {@code <appender-ref>} 引用（AppenderAttachable 标准机制，Joran 可解析）；
 * 编程装配可继续用 {@link #setDelegate}。
 */
public class PiiDesensitizingAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    private final PiiDesensitizer desensitizer = new PiiDesensitizer();
    private final AppenderAttachableImpl<ILoggingEvent> attachable = new AppenderAttachableImpl<>();

    /** 底层真实写入的 appender：优先 setDelegate 注入，其次 AppenderAttachable 首个 */
    private Appender<ILoggingEvent> delegate;

    /** 编程装配入口（兼容旧用法与单测） */
    public void setDelegate(Appender<ILoggingEvent> delegate) {
        this.delegate = delegate;
    }

    // ---- AppenderAttachable：支持 logback-spring.xml 的 <appender-ref> 装配 ----

    @Override
    public void addAppender(Appender<ILoggingEvent> newAppender) {
        attachable.addAppender(newAppender);
    }

    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return attachable.iteratorForAppenders();
    }

    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return attachable.getAppender(name);
    }

    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return attachable.isAttached(appender);
    }

    @Override
    public void detachAndStopAllAppenders() {
        attachable.detachAndStopAllAppenders();
    }

    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return attachable.detachAppender(appender);
    }

    @Override
    public boolean detachAppender(String name) {
        return attachable.detachAppender(name);
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
            // XML 装配路径：<appender-ref> 附加的底层 appender
            Iterator<Appender<ILoggingEvent>> it = iteratorForAppenders();
            if (it.hasNext()) {
                delegate = it.next();
            }
        }
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
