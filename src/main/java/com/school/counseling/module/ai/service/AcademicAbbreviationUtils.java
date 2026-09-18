package com.school.counseling.module.ai.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiện ích chuẩn hóa teencode và từ viết tắt học vụ HCMUTE trước khi tạo vector embedding.
 * Đảm bảo câu hỏi viết tắt của sinh viên không bị tụt điểm tương đồng ngữ nghĩa.
 */
public final class AcademicAbbreviationUtils {

    private static final Map<Pattern, String> ABBREVIATION_MAP = new LinkedHashMap<>();

    static {
        addRule("avđr|avdr", "anh văn đầu ra");
        addRule("đrl|drl", "điểm rèn luyện");
        addRule("đkhp|dkhp", "đăng ký học phần");
        addRule("hb", "học bổng");
        addRule("tn", "tốt nghiệp");
        addRule("kltn", "khóa luận tốt nghiệp");
        addRule("đatn", "đồ án tốt nghiệp");
        addRule("ctsv", "công tác sinh viên");
        addRule("pđt|pdt", "phòng đào tạo");
        addRule("hp", "học phần");
        addRule("tc", "tín chỉ");
        addRule("gpa", "điểm trung bình tích lũy");
    }

    private static void addRule(String regex, String replacement) {
        // Khớp nguyên từ không phân biệt hoa thường
        Pattern pattern = Pattern.compile("(?i)(?<!\\p{L})(" + regex + ")(?!\\p{L})", Pattern.UNICODE_CHARACTER_CLASS);
        ABBREVIATION_MAP.put(pattern, replacement);
    }

    private AcademicAbbreviationUtils() {}

    /**
     * Mở rộng các từ viết tắt trong câu hỏi sinh viên
     *
     * @param text Câu hỏi thô của sinh viên
     * @return Câu hỏi đã được mở rộng từ viết tắt
     */
    public static String expand(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        for (Map.Entry<Pattern, String> entry : ABBREVIATION_MAP.entrySet()) {
            Matcher matcher = entry.getKey().matcher(result);
            result = matcher.replaceAll(entry.getValue());
        }
        return result;
    }
}
