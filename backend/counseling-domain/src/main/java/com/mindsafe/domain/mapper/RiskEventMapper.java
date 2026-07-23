package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.RiskEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RiskEventMapper extends BaseMapper<RiskEvent> {
}
