package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.CounselingSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper
public interface CounselingSessionMapper extends BaseMapper<CounselingSession> {

    /**
     * 满意度评分分布聚合（P1-3 板块06 下移：教师侧分组统计 SQL 收敛 Mapper 层，
     * 参照 EmotionDiaryMapper.upsertCheckin 范式）。
     * <p>
     * tenantLine 显式跳过：SQL 已显式携带 tenant_id 参数（行隔离由 service 参数保证，
     * 与拦截器注入同源同值），避免租户插件解析 GROUP BY 子句的不确定性。
     *
     * @param tenantId 租户 ID（必填，行隔离唯一来源）
     * @param since    起始时间（可空；非空时仅统计该时点之后的会话）
     * @return rating → 会话数 分组行（{@code rating} 为评分、{@code cnt} 为数量）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT satisfaction_rating AS rating, COUNT(*) AS cnt
            FROM tenant_template.counseling_sessions
            WHERE tenant_id = #{tenantId}
              AND satisfaction_rating IS NOT NULL
            <if test="since != null">AND started_at &gt;= #{since}</if>
            GROUP BY satisfaction_rating
            </script>
            """)
    List<Map<String, Object>> countRatingDistribution(@Param("tenantId") UUID tenantId,
                                                      @Param("since") Instant since);
}
