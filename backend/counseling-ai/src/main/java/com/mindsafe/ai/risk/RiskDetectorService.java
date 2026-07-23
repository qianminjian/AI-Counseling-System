package com.mindsafe.ai.risk;

import com.mindsafe.common.dto.risk.RiskDetectionResult;

/**
 * 风险识别服务接口
 * <p>
 * M1：关键词硬规则检测。
 * M2+：语义分类 + 上下文评分 + LLM 辅助判断。
 */
public interface RiskDetectorService {

    /**
     * 检测学生消息中的风险信号
     *
     * @param message 学生消息文本
     * @return 风险检测结果
     */
    RiskDetectionResult detect(String message);
}
