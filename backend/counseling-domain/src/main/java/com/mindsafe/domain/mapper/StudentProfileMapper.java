package com.mindsafe.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindsafe.domain.entity.StudentProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StudentProfileMapper extends BaseMapper<StudentProfile> {
}
