package com.mindsafe.app.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;

/**
 * 日志事件包装器（AUDIT-P1-15）：仅替换消息内容，其余字段全部委托原事件。
 * <p>
 * 替代 logback 1.5.32 中已不存在的 {@code ProxyLoggingEvent}，供
 * {@link PiiDesensitizingAppender} 传递脱敏后的消息给底层 encoder。
 */
public class MaskedLoggingEvent implements ILoggingEvent {

    private final ILoggingEvent delegate;
    private final String maskedMessage;

    public MaskedLoggingEvent(ILoggingEvent delegate, String maskedMessage) {
        this.delegate = delegate;
        this.maskedMessage = maskedMessage;
    }

    @Override
    public String getThreadName() {
        return delegate.getThreadName();
    }

    @Override
    public Level getLevel() {
        return delegate.getLevel();
    }

    @Override
    public String getMessage() {
        return maskedMessage;
    }

    @Override
    public Object[] getArgumentArray() {
        return delegate.getArgumentArray();
    }

    @Override
    public String getFormattedMessage() {
        return maskedMessage;
    }

    @Override
    public String getLoggerName() {
        return delegate.getLoggerName();
    }

    @Override
    public LoggerContextVO getLoggerContextVO() {
        return delegate.getLoggerContextVO();
    }

    @Override
    public IThrowableProxy getThrowableProxy() {
        return delegate.getThrowableProxy();
    }

    @Override
    public StackTraceElement[] getCallerData() {
        return delegate.getCallerData();
    }

    @Override
    public boolean hasCallerData() {
        return delegate.hasCallerData();
    }

    @Override
    public List<Marker> getMarkerList() {
        return delegate.getMarkerList();
    }

    @Override
    public Map<String, String> getMDCPropertyMap() {
        return delegate.getMDCPropertyMap();
    }

    @Override
    public Map<String, String> getMdc() {
        return delegate.getMdc();
    }

    @Override
    public long getTimeStamp() {
        return delegate.getTimeStamp();
    }

    @Override
    public int getNanoseconds() {
        return delegate.getNanoseconds();
    }

    @Override
    public long getSequenceNumber() {
        return delegate.getSequenceNumber();
    }

    @Override
    public List<KeyValuePair> getKeyValuePairs() {
        return delegate.getKeyValuePairs();
    }

    @Override
    public void prepareForDeferredProcessing() {
        delegate.prepareForDeferredProcessing();
    }
}
