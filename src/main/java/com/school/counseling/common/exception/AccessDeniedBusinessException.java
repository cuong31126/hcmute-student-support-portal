package com.school.counseling.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN) // báo lỗi 403
public class AccessDeniedBusinessException extends RuntimeException {

    public AccessDeniedBusinessException(String message) { // NHẬN THÔNG BAOS caams quyen truy cap
        super(message);
    }
}
