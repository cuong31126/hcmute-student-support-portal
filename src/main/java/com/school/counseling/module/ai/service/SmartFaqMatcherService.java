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
        reloadCache();
    }

    /**
     * Nạp lại toàn bộ FAQs từ Database vào RAM Cache phục vụ so khớp tốc độ cao
     */
    public void reloadCache() {
        try {
            List<Faq> faqs = faqRepository.findAll();
            faqCache.clear();
            for (Faq f : faqs) {
                String deptName = (f.getDepartment() != null) ? f.getDepartment().getName() : "Phòng Đào tạo";
                Long deptId = (f.getDepartment() != null) ? f.getDepartment().getId() : null;
                faqCache.add(new FaqCacheItem(
                        f.getId(),
                        f.getQuestion(),
                        f.getAnswer(),
                        deptName,
                        deptId,
                        tokenize(f.getQuestion() + " " + (f.getKeywords() != null ? f.getKeywords() : ""))
                ));
            }
            log.info("Khởi tạo bộ tri thức Smart FAQ Matcher thành công: {} câu hỏi trong Cache", faqCache.size());
        } catch (Exception e) {
            log.warn("Lỗi khi nạp bộ nhớ Cache FAQ: {}", e.getMessage());
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

        String normQuery = normalize(studentQuestion);
        Set<String> queryTokens = tokenize(studentQuestion);

        List<FaqMatchResult> results = new ArrayList<>();

        for (FaqCacheItem item : faqCache) {
            if (departmentId != null && !Objects.equals(item.departmentId(), departmentId)) {
                continue;
            }

            double score = 0.0;
            if (!queryTokens.isEmpty() && !item.tokens().isEmpty()) {
                score = calculateSimilarity(queryTokens, item.tokens(), studentQuestion, item.question());
            }

            // Fallback: Nếu chuỗi câu hỏi chứa từ khóa tìm kiếm
            String normItemQuestion = normalize(item.question());
            if (normItemQuestion.contains(normQuery) || (normQuery.length() >= 3 && normItemQuestion.matches(".*\\b" + Pattern.quote(normQuery) + ".*"))) {
                score = Math.max(score, 0.65);
            }

            if (score >= 0.25) { // Ngưỡng tương đồng linh hoạt
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

        // Sắp xếp theo điểm tin cậy giảm dần và lấy tối đa 15 kết quả tốt nhất
        return results.stream()
                .sorted(Comparator.comparingDouble(FaqMatchResult::confidenceScore).reversed())
                .limit(15)
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
        if (normTarget.contains(normQuery)) {
            jaccard += 0.40;
        }

        return Math.min(1.0, jaccard);
    }

    private Set<String> tokenize(String text) {
        if (text == null) return Collections.emptySet();
        String normalized = normalize(text);
        String[] words = normalized.split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String w : words) {
            if (!w.trim().isEmpty()) {
                tokens.add(w.trim());
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
