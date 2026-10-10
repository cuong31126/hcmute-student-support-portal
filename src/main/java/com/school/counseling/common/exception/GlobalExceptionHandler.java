package com.school.counseling.common.exception;

import com.school.counseling.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * Xử lý ngoại lệ toàn cục cho toàn bộ hệ thống
 * xuwr lys resst api , trar veef json 
 * lang nghe toan bo cac loi ở các @RestController 
 */
@Slf4j
// hệ thống khởi động thấy @RestControlelrAdvice quét và tự nó tạo sẵn 1 object  là Spring Bean 
@RestControllerAdvice(annotations = org.springframework.web.bind.annotation.RestController.class)
public class GlobalExceptionHandler {

    /**
     * Bắt lỗi Validation dữ liệu đầu vào (@Valid DTO)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    // bắt lỗi khi user nhập thiếu , hôm các ô sai thành 1 ds chi tiết gửi về 
    public ResponseEntity<ApiResponse<Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("Loi xac thuc du lieu dau vao : {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Du lieu gui len khong hop le", errors));
    }

    /**
     * Bắt lỗi không tìm thấy tài nguyên (404 Not Found)
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Khong tim thay tai nguyen : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Bắt lỗi từ chối truy cập / can thiệp chéo đơn vị (403 Forbidden)
     */
    @ExceptionHandler({AccessDeniedBusinessException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Object>> handleAccessDeniedException(Exception ex) {
        log.warn("Truy cập bị từ chối : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Ban khong co quyen thuc hien thao tac tren tai nguyen nay (Phan quyen Khoa/Phong)"));
    }

    /**
     * Bắt lỗi Race Condition: Cán bộ bấm nhận Ticket đã được cán bộ khác nhận trước (409 Conflict)
     */
    @ExceptionHandler(TicketAlreadyClaimedException.class)
    public ResponseEntity<ApiResponse<Object>> handleTicketAlreadyClaimedException(TicketAlreadyClaimedException ex) {
        log.warn("Race Condition Claim Ticket: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Bắt lỗi tệp tin tải lên (400 Bad Request)
     */
    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidFileException(InvalidFileException ex) {
        log.warn("Loi tep tin : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Bắt lỗi dung lượng file vượt mức cho phép
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        log.warn("Dung luong tep vuot qua gioi han toi da : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Dung luong tep vuot qua gioi han toi da ( toi da 100mb)"));
    }

    /**
     * Bắt lỗi tham số không hợp lệ
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Tham so khong hop le : {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Bắt lỗi hệ thống nội bộ (500 Internal Server Error)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGlobalException(Exception ex) {
        log.error("Loi he thong khong mong muon : ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("He thong dang gap su co . vui long thu lai sau hoac lien he quan tri vien"));
    }
}
