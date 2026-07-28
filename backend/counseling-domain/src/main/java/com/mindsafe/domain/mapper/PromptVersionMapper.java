package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.PromptVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * Prompt 版本 Mapper（AI-005）
 */
@Mapper
public interface PromptVersionMapper extends BaseMapper<PromptVersion> {
}
