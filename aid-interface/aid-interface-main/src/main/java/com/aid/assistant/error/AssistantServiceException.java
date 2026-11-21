package com.aid.assistant.error;

import lombok.Getter;

/** Stable assistant API failure. */
@Getter
public class AssistantServiceException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int httpStatus;
    private final String errorCode;
    private final Object details;

    public AssistantServiceException(int httpStatus, String errorCode, String message, Object details)
    {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.details = details;
    }
}
