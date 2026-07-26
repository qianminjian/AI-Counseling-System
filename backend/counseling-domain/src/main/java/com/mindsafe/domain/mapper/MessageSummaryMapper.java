package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.MessageSummary;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageSummaryMapper extends BaseMapper<MessageSummary> {
}
