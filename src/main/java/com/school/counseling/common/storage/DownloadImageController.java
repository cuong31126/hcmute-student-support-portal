package com.school.counseling.common.storage;

import com.school.counseling.common.util.Constant;
import com.school.counseling.module.chat.entity.Attachment;
import com.school.counseling.module.chat.repository.AttachmentRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Controller phục vụ xem trực tiếp (inline preview) và tải về (download) tệp tin từ C:/upload.
 * Hỗ trợ các định dạng: PDF, Ảnh (PNG, JPG, WEBP), JSON, TXT, DOCX, XLSX, MP4...
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DownloadImageController {

    private final AttachmentRepository attachmentRepository;

    /**
     * Phục vụ ảnh cũ tương thích STS: /image?fname=ten_file.jpg
     */
    @GetMapping(value = "/image")
    public @ResponseBody void getImage(
            @RequestParam(name = "fname", required = true) String fileName,
            HttpServletRequest request,
            HttpServletResponse response) {
        serveFileInline(fileName, fileName, request, response);
    }

    /**
     * Phục vụ xem hoặc tải tài liệu trực tiếp từ URL /documents/{fileName}
     */
    @GetMapping(value = "/documents/{fileName}")
    public @ResponseBody void getDocument(
            @PathVariable("fileName") String fileName,
            @RequestParam(name = "download", defaultValue = "false") boolean download,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (download) {
            serveFileAttachment(fileName, fileName, response);
        } else {
            serveFileInline(fileName, fileName, request, response);
        }
    }

    /**
     * Tải về tệp đính kèm theo Attachment ID với đúng tên gốc tiếng Việt: /files/download/{id}
     */
    @GetMapping(value = "/files/download/{attachmentId}")
    public @ResponseBody void downloadAttachment(
            @PathVariable("attachmentId") Long attachmentId,
            HttpServletResponse response) {
        Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String storedName = extractStoredFileName(attachment.getFileUrl());
        serveFileAttachment(storedName, attachment.getFileName(), response);
    }

    /**
     * Xem nhanh (inline preview) tệp đính kèm theo Attachment ID: /files/view/{id}
     */
    @GetMapping(value = "/files/view/{attachmentId}")
    public @ResponseBody void previewAttachment(
            @PathVariable("attachmentId") Long attachmentId,
            HttpServletRequest request,
            HttpServletResponse response) {
        Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String storedName = extractStoredFileName(attachment.getFileUrl());
        serveFileInline(storedName, attachment.getFileName(), request, response);
    }

    // ─── PRIVATE HELPERS ─────────────────────────────────────────────────────────────────

    private void serveFileInline(String storedFileName, String displayFileName, HttpServletRequest request, HttpServletResponse response) {
        File file = findFileOnDisk(storedFileName);
        if (file == null || !file.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String mimeType = resolveMimeType(displayFileName != null ? displayFileName : storedFileName);
        response.setContentType(mimeType);
        response.setHeader("Content-Disposition", buildContentDisposition("inline", displayFileName != null ? displayFileName : storedFileName));
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Accept-Ranges", "bytes");

        long fileLength = file.length();
        String rangeHeader = (request != null) ? request.getHeader("Range") : null;

        // Xử lý HTTP 206 Partial Content nếu trình duyệt gửi Range header (tua video)
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String range = rangeHeader.substring(6);
            long start = 0;
            long end = fileLength - 1;
            int dashIndex = range.indexOf('-');
            if (dashIndex != -1) {
                String startStr = range.substring(0, dashIndex).trim();
                String endStr = range.substring(dashIndex + 1).trim();
                try {
                    if (!startStr.isEmpty()) {
                        start = Long.parseLong(startStr);
                    }
                    if (!endStr.isEmpty()) {
                        end = Long.parseLong(endStr);
                    }
                } catch (NumberFormatException nfe) {
                    log.debug("Lỗi parse Range header: {}", rangeHeader);
                }
            }

            if (start > end || start >= fileLength) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + fileLength);
                return;
            }

            long contentLength = end - start + 1;
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", String.format("bytes %d-%d/%d", start, end, fileLength));
            response.setContentLengthLong(contentLength);

            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
                 java.io.OutputStream os = response.getOutputStream()) {
                raf.seek(start);
                byte[] buffer = new byte[16384];
                long remaining = contentLength;
                while (remaining > 0) {
                    int readLen = (int) Math.min(buffer.length, remaining);
                    int read = raf.read(buffer, 0, readLen);
                    if (read == -1) break;
                    os.write(buffer, 0, read);
                    remaining -= read;
                }
                os.flush();
            } catch (Exception e) {
                log.debug("Client đóng kết nối Range Stream: {}", e.getMessage());
            }
            return;
        }

        // Tải toàn bộ file nếu không có Range header
        response.setContentLengthLong(fileLength);
        try (InputStream is = new FileInputStream(file)) {
            StreamUtils.copy(is, response.getOutputStream());
        } catch (Exception e) {
            log.warn("Lỗi khi truyền tệp inline: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void serveFileAttachment(String storedFileName, String downloadFileName, HttpServletResponse response) {
        File file = findFileOnDisk(storedFileName);
        if (file == null || !file.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String mimeType = resolveMimeType(downloadFileName != null ? downloadFileName : storedFileName);
        response.setContentType(mimeType);
        response.setContentLengthLong(file.length());
        response.setHeader("Content-Disposition", buildContentDisposition("attachment", downloadFileName != null ? downloadFileName : storedFileName));

        try (InputStream is = new FileInputStream(file)) {
            StreamUtils.copy(is, response.getOutputStream());
        } catch (Exception e) {
            log.warn("Lỗi khi tải tệp về: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private File findFileOnDisk(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return null;
        String cleanName = new File(fileName).getName();

        // Quét tất cả các thư mục lưu trữ có thể có trong C:/upload
        String[] possibleDirs = {
            Constant.DIR_DOCUMENTS,
            Constant.DIR,
            Constant.DIR_IMAGES,
            new File(Constant.DIR, "category").getAbsolutePath(),
            new File(Constant.DIR, "ticket_attachments").getAbsolutePath(),
            new File(Constant.DIR, "ticket_replies").getAbsolutePath(),
            Constant.DIR_VIDEOS
        };

        for (String dirPath : possibleDirs) {
            File f = new File(dirPath, cleanName);
            if (f.exists() && f.isFile()) {
                return f;
            }
        }

        return null;
    }

    private String extractStoredFileName(String fileUrl) {
        if (fileUrl == null) return "";
        if (fileUrl.contains("fname=")) {
            String name = fileUrl.substring(fileUrl.indexOf("fname=") + 6);
            if (name.contains("&")) {
                name = name.substring(0, name.indexOf('&'));
            }
            return name;
        }
        int lastSlash = fileUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < fileUrl.length() - 1) {
            return fileUrl.substring(lastSlash + 1);
        }
        int lastBackslash = fileUrl.lastIndexOf('\\');
        if (lastBackslash >= 0 && lastBackslash < fileUrl.length() - 1) {
            return fileUrl.substring(lastBackslash + 1);
        }
        return fileUrl;
    }

    private String resolveMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".json")) return "application/json;charset=UTF-8";
        if (lower.endsWith(".txt")) return "text/plain;charset=UTF-8";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".rar")) return "application/vnd.rar";
        if (lower.endsWith(".7z")) return "application/x-7z-compressed";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private String buildContentDisposition(String type, String originalFilename) {
        String clean = originalFilename != null ? originalFilename.replaceAll("[\"\\\\]", "") : "download";
        String encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8).replace("+", "%20");
        return type + "; filename=\"" + clean + "\"; filename*=UTF-8''" + encoded;
    }
}
