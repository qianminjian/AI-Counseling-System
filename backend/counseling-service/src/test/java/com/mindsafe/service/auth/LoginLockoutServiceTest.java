package com.mindsafe.service.auth;

import com.mindsafe.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginLockoutServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginLockoutService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new LoginLockoutService(redisTemplate);
    }

    @Test
    void 无失败记录时不锁定() {
        when(valueOperations.get("login_fail:teacher1")).thenReturn(null);
        assertDoesNotThrow(() -> service.checkLockout("teacher1"));
    }

    @Test
    void 失败次数未达阈值不锁定() {
        when(valueOperations.get("login_fail:teacher1")).thenReturn("3");
        assertDoesNotThrow(() -> service.checkLockout("teacher1"));
    }

    @Test
    void 失败5次触发锁定() {
        when(valueOperations.get("login_fail:teacher1")).thenReturn("5");
        when(redisTemplate.getExpire("login_fail:teacher1")).thenReturn(600L);
        BizException ex = assertThrows(BizException.class, () -> service.checkLockout("teacher1"));
        assertTrue(ex.getMessage().contains("分钟后重试"));
    }

    @Test
    void 锁定提示包含剩余时间() {
        when(valueOperations.get("login_fail:admin")).thenReturn("7");
        when(redisTemplate.getExpire("login_fail:admin")).thenReturn(120L);
        BizException ex = assertThrows(BizException.class, () -> service.checkLockout("admin"));
        assertTrue(ex.getMessage().contains("3 分钟"));
    }

    @Test
    void 首次失败设置过期时间() {
        when(valueOperations.increment("login_fail:user1")).thenReturn(1L);
        service.recordFailure("user1");
        verify(redisTemplate).expire("login_fail:user1", Duration.ofMinutes(15));
    }

    @Test
    void 非首次失败不重置过期时间() {
        when(valueOperations.increment("login_fail:user1")).thenReturn(3L);
        service.recordFailure("user1");
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void 登录成功清除失败计数() {
        when(redisTemplate.delete("login_fail:user1")).thenReturn(true);
        service.clearFailures("user1");
        verify(redisTemplate).delete("login_fail:user1");
    }
}
