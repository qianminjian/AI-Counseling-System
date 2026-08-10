package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.Device;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}
