package com.mindsafe.service.alert;

/**
 * 告警服务接口（OPS-004）
 * <p>
 * 统一告警出口：服务宕机/错误率/LLM 超时/质量低分等场景触发。
 * 实现可切换：企微 webhook / 钉钉 webhook / 日志降级。
 */
public interface AlertService {

    /**
     * 发送告警
     *
     * @param level   告警级别：critical / warning / info
     * @param title   告警标题（简短）
     * @param detail  告警详情
     */
    void sendAlert(AlertLevel level, String title, String detail);

    enum AlertLevel {
        CRITICAL, WARNING, INFO
    }
}
