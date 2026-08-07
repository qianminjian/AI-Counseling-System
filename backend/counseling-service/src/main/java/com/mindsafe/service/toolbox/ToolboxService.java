package com.mindsafe.service.toolbox;

import com.mindsafe.domain.entity.User;
import com.mindsafe.domain.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 工具箱服务（T4 批次C：学生年级解析下沉，Controller 不再直查 Mapper）。
 */
@Service
public class ToolboxService {

    private final UserMapper userMapper;

    public ToolboxService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 按 ID 查询用户（null 表示不存在） */
    public User findUserById(UUID userId) {
        return userMapper.selectById(userId);
    }
}
