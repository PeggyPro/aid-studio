package com.aid.director;

import lombok.Getter;

/** 虚拟相机业务错误。 */
@Getter
public final class DirectorException extends RuntimeException {
    private final String errorCode;
    private final int status;

    public DirectorException(String code, int status, String message) {
        super(message);
        this.errorCode = code;
        this.status = status;
    }

    public static DirectorException invalid() {
        return new DirectorException("INVALID_ARGUMENT", 400, "参数不正确");
    }

    public static DirectorException conflict(String code) {
        return new DirectorException(code, 409, "状态已变化，请刷新");
    }

    public static DirectorException denied() {
        return new DirectorException("FORBIDDEN", 403, "无权执行此操作");
    }
}
