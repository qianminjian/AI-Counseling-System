package com.mindsafe.common.exception;

import com.mindsafe.common.dto.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BizException 业务异常单测：三种构造器 + code/errorCode 取值契约。
 */
class BizExceptionTest {

    @Test
    @DisplayName("ErrorCode 构造：code 取自枚举，message 取枚举默认")
    void constructor_enumOnly() {
        BizException e = new BizException(ErrorCode.FORBIDDEN);

        assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN.code());
        assertThat(e.getMessage()).isEqualTo(ErrorCode.FORBIDDEN.message());
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("ErrorCode + detail 构造：message 用自定义文案")
    void constructor_enumWithDetail() {
        BizException e = new BizException(ErrorCode.PARAM_INVALID, "手机号格式错误");

        assertThat(e.getCode()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(e.getMessage()).isEqualTo("手机号格式错误");
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("int 构造器：errorCode 为 null（兼容语义）")
    void constructor_int() {
        BizException e = new BizException(9999, "兼容错误码");

        assertThat(e.getCode()).isEqualTo(9999);
        assertThat(e.getMessage()).isEqualTo("兼容错误码");
        assertThat(e.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("getErrorCode：暴露枚举供全局异常处理器映射 httpStatus")
    void getErrorCode() {
        assertThat(new BizException(ErrorCode.RESOURCE_NOT_FOUND).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(new BizException(1001, "x").getErrorCode()).isNull();
    }
}
