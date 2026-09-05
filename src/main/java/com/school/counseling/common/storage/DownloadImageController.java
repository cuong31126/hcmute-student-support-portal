package com.school.counseling.common.storage;

import com.school.counseling.common.util.Constant;
import org.apache.commons.io.IOUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Controller phục vụ xem và tải ảnh từ thư mục Constant.DIR (C:\upload)
 * URL truy cập: /image?fname=ten_file.jpg
 */
@Controller
public class DownloadImageController {

    @GetMapping(value = "/image")
    public @ResponseBody void getImage(
            @RequestParam(name = "fname", required = true) String fileName,
            HttpServletResponse response) {
        
        try {
            // Tìm trong thư mục con images hoặc thư mục gốc C:\upload
            File file = new File(Constant.DIR_IMAGES, fileName);
            if (!file.exists()) {
                file = new File(Constant.DIR, fileName);
            }

            if (file.exists()) {
                String mimeType = "image/jpeg";
                if (fileName.toLowerCase().endsWith(".png")) {
                    mimeType = MediaType.IMAGE_PNG_VALUE;
                } else if (fileName.toLowerCase().endsWith(".gif")) {
                    mimeType = MediaType.IMAGE_GIF_VALUE;
                } else if (fileName.toLowerCase().endsWith(".webp")) {
                    mimeType = "image/webp";
                }
                
                response.setContentType(mimeType);
                try (InputStream is = new FileInputStream(file)) {
                    IOUtils.copy(is, response.getOutputStream());
                }
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
