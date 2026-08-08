package com.mindsafe.service.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 暖场护栏配置（B2 收编：nudge 阈值单一配置源，消灭 Lua/快照双真值常量漂移）。
 * <p>
 * 绑定 application.yml {@code mindsafe.conversation.nudge} 子树；
 * Lua 原子判定（RedisSessionStateStore.tryNudge）与 Redis 真值预判读
 * （getNudgeCount/getLastNudgeAt，BA-09 单一真值源）统一引用本配置，改阈值只改一处。
 */
@Component
@ConfigurationProperties(prefix = "mindsafe.conversation.nudge")
public class NudgeProperties {

    /** 暖场护栏：单会话内连续暖场次数上限（默认 2，对齐 T5 原值） */
    private int maxCount = 2;

    /** 暖场最小间隔秒（默认 20，对齐 T5 原值） */
    private long minIntervalSeconds = 20;

    public int getMaxCount() {
        return maxCount;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }

    public long getMinIntervalSeconds() {
        return minIntervalSeconds;
    }

    public void setMinIntervalSeconds(long minIntervalSeconds) {
        this.minIntervalSeconds = minIntervalSeconds;
    }
}
