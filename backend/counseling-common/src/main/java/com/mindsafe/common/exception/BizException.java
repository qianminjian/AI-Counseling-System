package com.mindsafe.common.exception;

import com.mindsafe.common.dto.ErrorCode;

/**
 * 业务异常（携带错误码，由全局异常处理器统一转为 ApiResponse）
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.code = errorCode.code();
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail);
        this.code = errorCode.code();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
