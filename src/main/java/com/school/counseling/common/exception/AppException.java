package com.school.counseling.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// lỗi chung của ứng dụng , ngoại lệ không mong muốn
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // báo lỗi 500 INTERNAL SERVER ERROR
public class AppException extends RuntimeException {

    public AppException(String message) {
        super(message);
    }

    public AppException(String message, Throwable cause) {
        super(message, cause);
    }
}
