package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.DegradationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DegradationEventMapper extends BaseMapper<DegradationEvent> {

    /**
     * DB 幂等写入（专题 F P1-4，联动板块05 P0-4）：detector 写 auto 事件专用。
     * <p>
     * 多实例下防抖窗口内重复写入同 dedup_key → 唯一索引冲突 → {@code ON CONFLICT DO NOTHING}
     * 静默跳过（不抛异常、不污染时间线）。manual 事件（管理端切换写库）仍走 BaseMapper.insert
     * （dedup_key 为 NULL，partial 唯一索引不约束），两条写入路径互不干扰。
     *
     * @return 实际插入行数（0 = 同窗口重复写入被幂等跳过）
     */
    @Insert("INSERT INTO tenant_template.degradation_events " +
            "(event_id, point, from_state, to_state, trigger_type, operator, detail, occurred_at, dedup_key) " +
            "VALUES (#{eventId}, #{point}, #{fromState}, #{toState}, #{triggerType}, #{operator}, #{detail}, #{occurredAt}, #{dedupKey}) " +
            "ON CONFLICT DO NOTHING")
    int insertOnConflictDoNothing(DegradationEvent event);
}
