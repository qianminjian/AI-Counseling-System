package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
