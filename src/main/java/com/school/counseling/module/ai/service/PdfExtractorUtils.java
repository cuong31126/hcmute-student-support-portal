package com.school.counseling.module.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiện ích trích xuất và phân mảnh (chunking) tài liệu quy chế PDF theo chuẩn Academic Enterprise.
 * Hỗ trợ bóc tách từng trang, bảo toàn Điều/Khoản/Mục và tiêm Header Context vào từng vector chunk.
 */
@Slf4j
public final class PdfExtractorUtils {

    private static final int DEFAULT_CHUNK_SIZE = 450;
    private static final int DEFAULT_OVERLAP = 60;

    // Pattern nhận diện số công văn (ví dụ: 1084/QĐ-ĐHSPKT, 2059/TB-ĐHSPKT, 124/TB-ĐHSPKT...)
    private static final Pattern DOC_CODE_PATTERN = Pattern.compile(
            "(?:Số|Số:)?\\s*(\\d{1,5}(?:\\.\\d+)?/[A-ZĐa-z0-9_.-]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Pattern nhận diện tiêu đề Điều/Mục/Chương trong văn bản quy chế pháp quy
    private static final Pattern ARTICLE_HEADER_PATTERN = Pattern.compile(
            "^(?:Điều|ĐIỀU|Mục|MỤC|Chương|CHƯƠNG)\\s+([0-9IVXLCDM]+)[\\.:\\s-]*([^\\n\\r]*)",
            Pattern.MULTILINE
    );

    private PdfExtractorUtils() {}

    /**
     * DTO lưu trữ nội dung từng trang của file PDF
     */
    public record PageContent(int pageNumber, String text) {}

    /**
     * DTO lưu trữ đoạn phân mảnh có cấu trúc ngữ nghĩa kèm Header ngữ cảnh
     */
    public record StructuralChunk(
            int pageNumber,
            String articleHeader,
            String injectedContent,
            String rawContent
    ) {}

    /**
     * Đọc toàn bộ văn bản từ file PDF (Backward-compatible)
     */
    public static String extractText(File pdfFile) throws IOException {
        List<PageContent> pages = extractPagesWithInfo(pdfFile);
        StringBuilder sb = new StringBuilder();
        for (PageContent page : pages) {
            sb.append(page.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Trích xuất nội dung từng trang một kèm kiểm tra tính toàn vẹn của layer chữ
     */
    public static List<PageContent> extractPagesWithInfo(File pdfFile) throws IOException {
        if (pdfFile == null || !pdfFile.exists()) {
            throw new IllegalArgumentException("Tệp PDF không tồn tại hoặc đường dẫn không hợp lệ");
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("Tệp PDF bị khóa mật khẩu mã hóa, không thể đọc");
            }

            int pageCount = document.getNumberOfPages();
            List<PageContent> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int totalLength = 0;
            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);
                String cleaned = (pageText != null) ? pageText.trim() : "";
                totalLength += cleaned.length();
                pages.add(new PageContent(i, cleaned));
            }

            if (totalLength < 30) {
                throw new IllegalArgumentException(
                        "Tệp PDF không có lớp văn bản số (có thể là file scan/ảnh chụp). " +
                        "Vui lòng tải lên tài liệu PDF có thể chọn và copy chữ."
                );
            }

            return pages;
        }
    }

    /**
     * Phân mảnh văn bản cấu trúc theo Điều/Khoản và tiêm Context Header
     */
    public static List<StructuralChunk> chunkStructural(
            List<PageContent> pages,
            String documentTitle,
            String documentCode,
            int year,
            String issuer
    ) {
        List<StructuralChunk> chunks = new ArrayList<>();
        if (pages == null || pages.isEmpty()) {
            return chunks;
        }

        String safeCode = (documentCode != null && !documentCode.isBlank()) ? documentCode : "Không số";
        String safeIssuer = (issuer != null && !issuer.isBlank()) ? issuer : "HCMUTE";
        String currentArticleHeader = "Điều khoản chung";

        for (PageContent page : pages) {
            String pageText = page.text();
            if (pageText.isBlank()) {
                continue;
            }

            // Tách theo đoạn văn trong trang
            String[] paragraphs = pageText.split("\\r?\\n\\r?\\n");
            StringBuilder currentSegment = new StringBuilder();

            for (String p : paragraphs) {
                String trimmedP = p.trim().replaceAll("\\s+", " ");
                if (trimmedP.isEmpty()) {
                    continue;
                }

                // Kiểm tra xem đoạn này có chứa định danh Điều/Mục mới không
                Matcher articleMatcher = ARTICLE_HEADER_PATTERN.matcher(trimmedP);
                if (articleMatcher.find()) {
                    currentArticleHeader = articleMatcher.group(0).trim();
                }

                if (currentSegment.length() + trimmedP.length() > DEFAULT_CHUNK_SIZE) {
                    if (currentSegment.length() > 0) {
                        String raw = currentSegment.toString().trim();
                        String injected = buildInjectedHeader(documentTitle, safeCode, year, page.pageNumber(), currentArticleHeader, safeIssuer)
                                + "\n[NỘI DUNG]:\n" + raw;
                        chunks.add(new StructuralChunk(page.pageNumber(), currentArticleHeader, injected, raw));

                        // Giữ lại overlap
                        int currentLen = currentSegment.length();
                        int overlapStart = Math.max(0, currentLen - DEFAULT_OVERLAP);
                        String overlapStr = currentSegment.substring(overlapStart);
                        currentSegment = new StringBuilder(overlapStr).append(" ");
                    }
                }

                currentSegment.append(trimmedP).append("\n");
            }

            if (currentSegment.length() > 0 && !currentSegment.toString().trim().isEmpty()) {
                String raw = currentSegment.toString().trim();
                String injected = buildInjectedHeader(documentTitle, safeCode, year, page.pageNumber(), currentArticleHeader, safeIssuer)
                        + "\n[NỘI DUNG]:\n" + raw;
                chunks.add(new StructuralChunk(page.pageNumber(), currentArticleHeader, injected, raw));
            }
        }

        return chunks;
    }

    private static String buildInjectedHeader(String title, String code, int year, int page, String article, String issuer) {
        return String.format(
                "[VĂN BẢN: %s | SỐ HIỆU: %s | NĂM BAN HÀNH: %d | TRANG: %d | ĐIỀU/MỤC: %s | ĐƠN VỊ: %s]",
                title, code, year, page, article, issuer
        );
    }

    /**
     * Bóc tách mã số hiệu công văn tự động từ văn bản trang 1
     */
    public static String extractDocumentCode(String firstPageText) {
        if (firstPageText == null || firstPageText.isBlank()) {
            return null;
        }
        Matcher matcher = DOC_CODE_PATTERN.matcher(firstPageText);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Phân loại tự động danh mục nghiệp vụ dựa trên tiêu đề và nội dung công văn
     */
    public static String detectCategory(String title, String content) {
        String combined = ((title != null ? title : "") + " " + (content != null ? content : "")).toLowerCase();
        if (combined.contains("học phí") || combined.contains("lệ phí") || combined.contains("thu hp")) {
            return "HOC_PHI";
        }
        if (combined.contains("tốt nghiệp") || combined.contains("đồ án") || combined.contains("khóa luận") || combined.contains("lễ phục")) {
            return "TOT_NGHIEP";
        }
        if (combined.contains("học bổng") || combined.contains("trợ cấp") || combined.contains("hỗ trợ")) {
            return "HOC_BONG";
        }
        if (combined.contains("lịch thi") || combined.contains("kỳ thi") || combined.contains("thi avdv") || combined.contains("hoãn thi")) {
            return "LICH_THI";
        }
        if (combined.contains("tiếng anh") || combined.contains("ngoại ngữ") || combined.contains("cambridge") || combined.contains("toeic")) {
            return "NGOAI_NGU";
        }
        if (combined.contains("khen thưởng") || combined.contains("kỷ luật") || combined.contains("rèn luyện")) {
            return "KHEN_THUONG_REN_LUYEN";
        }
        if (combined.contains("đăng ký môn") || combined.contains("dkmh") || combined.contains("thời khóa biểu")) {
            return "DANG_KY_MON";
        }
        return "QUY_CHE_CHUNG";
    }

    /**
     * Băm nhỏ văn bản (Backward-compatible)
     */
    public static List<String> chunkText(String fullText) {
        return chunkText(fullText, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public static List<String> chunkText(String fullText, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (fullText == null || fullText.isBlank()) {
            return chunks;
        }

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
