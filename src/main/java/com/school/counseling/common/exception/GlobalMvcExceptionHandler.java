package com.school.counseling.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * dành rieng vs web controller trả về giao diện thymeleaf
 * Xử lý ngoại lệ toàn cục cho các Controller giao diện MVC (Thymeleaf Views)
 * Đảm bảo hiển thị Toast thông báo lỗi ở góc phải màn hình thay vì làm văng lỗi
 * 500 hay ngắt kết nối.
 */
@Slf4j
@ControllerAdvice(annotations = Controller.class)
public class GlobalMvcExceptionHandler {

    /**
     * Bắt lỗi tải lên tệp vượt quá kích thước tối đa cho phép (100MB)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            RedirectAttributes redirectAttributes,
            HttpServletRequest request) {

        log.warn("Loi tai tep tin vuot qua da   dung luong (MVC) : {}", ex.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", "Teo tin vuot qua dung luong cho phep (100MB)!");

        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null && !referer.isBlank() ? referer : "/feed/official");
    }

    /**
     * Bắt lỗi tệp tin không hợp lệ (định dạng, corrupt, rỗng...)
     */
    @ExceptionHandler(InvalidFileException.class)
    public String handleInvalidFileException(
            InvalidFileException ex,
            RedirectAttributes redirectAttributes,
            HttpServletRequest request) {

        log.warn("Loi tep tin khong hop le (MVC) : {}", ex.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());

        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null && !referer.isBlank() ? referer : "/feed/official");
    }

    /**
     * Bắt lỗi không có quyền truy cập / phân quyền
     */
    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDeniedException(
            AccessDeniedException ex,
            RedirectAttributes redirectAttributes,
            HttpServletRequest request) {

        log.warn("Loi tu choi quyen truy cap (MVC) : {}", ex.getMessage());
        redirectAttributes.addFlashAttribute("errorMessage", "Ban khong co quyen thuc hien thao tac nay.");

        String referer = request.getHeader("Referer");
        return "redirect:" + (referer != null && !referer.isBlank() ? referer : "/feed/official");
    }
}
