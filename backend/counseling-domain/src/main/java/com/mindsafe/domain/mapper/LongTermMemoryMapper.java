package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.LongTermMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 长期记忆 Mapper（AI-008）
 */
@Mapper
public interface LongTermMemoryMapper extends BaseMapper<LongTermMemory> {
}
