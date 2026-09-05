package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.counseling.module.ai.entity.Faq;
import com.school.counseling.module.ai.repository.FaqRepository;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartFaqMatcherService {

    private final FaqRepository faqRepository;
    private final DepartmentRepository departmentRepository;
    private final ObjectMapper objectMapper;

    // Cache bộ nhớ phục vụ so khớp tức thì
    private final List<FaqCacheItem> faqCache = new ArrayList<>();

    @PostConstruct
    public void initFaqKnowledgeBase() {
        try {
            // 1. Nếu Database chưa có dữ liệu FAQ, nạp mẫu từ form_demo/faq_dataset.json
            if (faqRepository.count() == 0) {
                seedFaqsFromJson();
            }

            // 2. Nạp toàn bộ FAQs vào bộ nhớ Cache phục vụ so khớp tốc độ cao
            List<Faq> faqs = faqRepository.findAll();
            faqCache.clear();
            for (Faq f : faqs) {
                faqCache.add(new FaqCacheItem(
                        f.getId(),
                        f.getQuestion(),
                        f.getAnswer(),
                        f.getDepartment().getName(),
                        f.getDepartment().getId(),
                        tokenize(f.getQuestion() + " " + (f.getKeywords() != null ? f.getKeywords() : ""))
                ));
            }
            log.info("Khởi tạo bộ tri thức Smart FAQ Matcher thành công: {} câu hỏi", faqCache.size());
        } catch (Exception e) {
            log.warn("Lỗi khi khởi tạo bộ tri thức FAQ: {}", e.getMessage());
        }
    }

    /**
     * So khớp thông minh câu hỏi sinh viên với kho tri thức
     * @param studentQuestion Câu hỏi do sinh viên gõ
     * @param departmentId Đơn vị (nếu có lọc)
     * @return Danh sách các gợi ý khớp tốt nhất kèm điểm tin cậy
     */
    public List<FaqMatchResult> matchQuestion(String studentQuestion, Long departmentId) {
        if (studentQuestion == null || studentQuestion.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> queryTokens = tokenize(studentQuestion);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<FaqMatchResult> results = new ArrayList<>();

        for (FaqCacheItem item : faqCache) {
            if (departmentId != null && !Objects.equals(item.departmentId(), departmentId)) {
                continue;
            }

            double score = calculateSimilarity(queryTokens, item.tokens(), studentQuestion, item.question());
            if (score >= 0.35) { // Ngưỡng tương đồng tối thiểu
                results.add(new FaqMatchResult(
                        item.id(),
                        item.question(),
                        item.answer(),
                        item.departmentName(),
                        item.departmentId(),
                        Math.round(score * 100.0) / 100.0
                ));
            }
        }

        // Sắp xếp theo điểm tin cậy giảm dần và lấy tối đa 5 kết quả tốt nhất
        return results.stream()
                .sorted(Comparator.comparingDouble(FaqMatchResult::confidenceScore).reversed())
                .limit(5)
                .collect(Collectors.toList());
    }

    private double calculateSimilarity(Set<String> queryTokens, Set<String> targetTokens, String rawQuery, String rawTarget) {
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0.0;

        // Jaccard similarity trên tập từ vựng
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(targetTokens);

        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(targetTokens);

        double jaccard = (double) intersection.size() / union.size();

        // Tăng trọng số nếu chuỗi con khớp chính xác
        String normQuery = normalize(rawQuery);
        String normTarget = normalize(rawTarget);
        if (normTarget.contains(normQuery) || normQuery.contains(normTarget)) {
            jaccard += 0.35;
        }

        return Math.min(1.0, jaccard);
    }

    private Set<String> tokenize(String text) {
        if (text == null) return Collections.emptySet();
        String normalized = normalize(text);
        String[] words = normalized.split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String w : words) {
            if (w.length() >= 2) {
                tokens.add(w);
            }
        }
        return tokens;
    }

    private String normalize(String input) {
        if (input == null) return "";
        String temp = Normalizer.normalize(input.toLowerCase(), Normalizer.Form.NFD);
        Pattern pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String noAccents = pattern.matcher(temp).replaceAll("").replace("đ", "d");
        return noAccents.replaceAll("[^a-z0-9\\s]", " ").trim();
    }

    private void seedFaqsFromJson() {
        try {
            File file = new File("form_demo/faq_dataset.json");
            if (!file.exists()) {
                log.info("Không tìm thấy form_demo/faq_dataset.json, bỏ qua bước nạp mẫu");
                return;
            }

            Department defaultDept = departmentRepository.findAll().stream().findFirst().orElse(null);
            if (defaultDept == null) {
                return;
            }

            List<Map<String, Object>> records = objectMapper.readValue(file, new TypeReference<>() {});
            List<Faq> entities = new ArrayList<>();

            int count = 0;
            for (Map<String, Object> r : records) {
                if (count >= 500) break; // Nạp 500 câu đầu tiên vào DB để tối ưu thời gian khởi động
                String title = (String) r.get("title");
                List<Map<String, String>> replies = (List<Map<String, String>>) r.get("replies");

                String answer = "Vui lòng liên hệ trực tiếp phòng ban phụ trách để được hướng dẫn chi tiết.";
                if (replies != null && !replies.isEmpty()) {
                    answer = replies.get(0).get("content");
                }

                if (title != null && !title.trim().isEmpty() && answer != null) {
                    entities.add(Faq.builder()
                            .question(title.trim())
                            .answer(answer.trim())
                            .department(defaultDept)
                            .category("Học vụ")
                            .keywords(title)
                            .viewCount(1)
                            .isActive(true)
                            .build());
                    count++;
                }
            }

            if (!entities.isEmpty()) {
                faqRepository.saveAll(entities);
                log.info("Đã nạp thành công {} câu hỏi FAQ thực tế vào Database!", entities.size());
            }
        } catch (Exception e) {
            log.warn("Không thể nạp dữ liệu từ faq_dataset.json: {}", e.getMessage());
        }
    }

    public record FaqMatchResult(
            Long id,
            String question,
            String answer,
            String departmentName,
            Long departmentId,
            double confidenceScore
    ) {}

    private record FaqCacheItem(
            Long id,
            String question,
            String answer,
            String departmentName,
            Long departmentId,
            Set<String> tokens
    ) {}
}
