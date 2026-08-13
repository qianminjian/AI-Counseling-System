package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.MessageSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper
public interface MessageSummaryMapper extends BaseMapper<MessageSummary> {

    /**
     * 情绪标签分布聚合（P1-3 板块06 下移：教师侧分组统计 SQL 收敛 Mapper 层，
     * 参照 EmotionDiaryMapper.upsertCheckin 范式）。
     * <p>
     * tenantLine 显式跳过：SQL 已显式携带 tenant_id 参数（行隔离由 service 参数保证，
     * 与拦截器注入同源同值），避免租户插件解析 GROUP BY 子句的不确定性。
     *
     * @param tenantId   租户 ID（必填，行隔离唯一来源）
     * @param senderType 消息发送方类型（如 student/ai，见 User.USER_TYPE_*）
     * @param since      起始时间（必填；时间窗由调用方控制）
     * @return emotion_label → 数量 分组行（{@code emotion_label} 为情绪标签、{@code cnt} 为数量）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT emotion_label, COUNT(*) AS cnt
            FROM tenant_template.message_summaries
            WHERE tenant_id = #{tenantId}
              AND sender_type = #{senderType}
              AND created_at >= #{since}
              AND emotion_label IS NOT NULL
            GROUP BY emotion_label
            """)
    List<Map<String, Object>> countEmotionDistribution(@Param("tenantId") UUID tenantId,
                                                       @Param("senderType") String senderType,
                                                       @Param("since") Instant since);
}
