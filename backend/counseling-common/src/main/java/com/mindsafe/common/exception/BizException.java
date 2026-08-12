package com.mindsafe.common.exception;

import com.mindsafe.common.dto.ErrorCode;

/**
 * 业务异常（携带错误码，由全局异常处理器统一转为 ApiResponse）
 * <p>
 * 审计 F7：构造时保留 ErrorCode 枚举引用，GlobalExceptionHandler 从枚举直接取 httpStatus，
 * 不再维护魔法 switch 映射。int 构造器保留仅为 API 兼容（当前无调用方）。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.code = errorCode.code();
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail);
        this.code = errorCode.code();
        this.errorCode = errorCode;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = null;
    }

    public int getCode() {
        return code;
    }

    /** 构造时传入的错误码枚举（int 构造器 → null；GlobalExceptionHandler 据此取 httpStatus） */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
