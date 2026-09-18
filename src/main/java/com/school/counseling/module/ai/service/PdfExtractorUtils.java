package com.school.counseling.module.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiện ích trích xuất và phân mảnh (chunking) tài liệu quy chế PDF.
 * Kiểm tra tính hợp lệ của text layer và phân mảnh thông minh có overlap.
 */
@Slf4j
public final class PdfExtractorUtils {

    private static final int DEFAULT_CHUNK_SIZE = 450;
    private static final int DEFAULT_OVERLAP = 60;

    private PdfExtractorUtils() {}

    /**
     * Đọc toàn bộ văn bản từ file PDF, bắt lỗi scanned PDF rỗng
     */
    public static String extractText(File pdfFile) throws IOException {
        if (pdfFile == null || !pdfFile.exists()) {
            throw new IllegalArgumentException("Tệp PDF không tồn tại hoặc đường dẫn không hợp lệ");
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("Tệp PDF bị khóa mật khẩu mã hóa, không thể đọc");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Giúp giữ thứ tự đọc bảng biểu tốt hơn
            String text = stripper.getText(document);

            if (text == null || text.trim().length() < 50) {
                throw new IllegalArgumentException(
                        "Tệp PDF không có lớp văn bản số (có thể là file scan/ảnh chụp). " +
                        "Vui lòng tải lên tài liệu PDF có thể chọn và copy chữ."
                );
            }

            return text.trim();
        }
    }

    /**
     * Băm nhỏ văn bản thành danh sách các đoạn chunk có độ dài phù hợp và overlap
     */
    public static List<String> chunkText(String fullText) {
        return chunkText(fullText, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public static List<String> chunkText(String fullText, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (fullText == null || fullText.isBlank()) {
            return chunks;
        }

        // Tách theo đoạn văn
        String[] paragraphs = fullText.split("\\r?\\n\\r?\\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String p : paragraphs) {
            String trimmedP = p.trim().replaceAll("\\s+", " ");
            if (trimmedP.isEmpty()) {
                continue;
            }

            if (currentChunk.length() + trimmedP.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    // Giữ lại phần overlap từ cuối chunk trước
                    int currentLen = currentChunk.length();
                    int overlapStart = Math.max(0, currentLen - overlap);
                    String overlapStr = currentChunk.substring(overlapStart);
                    currentChunk = new StringBuilder(overlapStr).append(" ");
                }
            }

            currentChunk.append(trimmedP).append("\n");
        }

        if (currentChunk.length() > 0 && !currentChunk.toString().trim().isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }
}
