package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.entity.Faq;
import com.school.counseling.module.ai.repository.FaqRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartFaqMatcherService {

    private final FaqRepository faqRepository;

    // Bộ nhớ Cache RAM phục vụ tìm kiếm siêu tốc (< 5ms)
    private final List<FaqCacheItem> faqCache = new ArrayList<>();

    @PostConstruct
    public void initFaqKnowledgeBase() {
        reloadCache();
    }

    /**
     * Nạp toàn bộ FAQs từ Database vào RAM Cache
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void reloadCache() {
        try {
            List<Faq> faqs = faqRepository.findAllActiveWithDepartment();
            faqCache.clear();
            for (Faq f : faqs) {
                String deptName = (f.getDepartment() != null) ? f.getDepartment().getName() : "Phòng Đào tạo";
                Long deptId = (f.getDepartment() != null) ? f.getDepartment().getId() : null;
                String category = (f.getCategory() != null) ? f.getCategory() : "Học vụ";
                String keywords = (f.getKeywords() != null) ? f.getKeywords() : "";

                faqCache.add(new FaqCacheItem(
                        f.getId(),
                        f.getQuestion(),
                        f.getAnswer(),
                        deptName,
                        deptId,
                        category,
                        normalize(f.getQuestion()),
                        normalize(keywords),
                        normalize(f.getAnswer())
                ));
            }
            log.info("Nạp bộ nhớ Cache FAQ thành công: {} câu hỏi", faqCache.size());
        } catch (Exception e) {
            log.error("Lỗi khi nạp bộ nhớ Cache FAQ: ", e);
        }
    }

    /**
     * Tìm kiếm từ khóa chính xác trong kho câu hỏi học vụ
     * @param query Từ khóa do người dùng nhập (hỗ trợ cả tiếng Việt có dấu và không dấu)
     * @param departmentId Đơn vị (nếu có lọc)
     * @return Danh sách câu hỏi phù hợp nhất sắp xếp theo độ liên quan
     */
    public List<FaqMatchResult> matchQuestion(String query, Long departmentId) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normQuery = normalize(query);
        String[] rawTokens = normQuery.split("\\s+");
        List<String> queryWords = new ArrayList<>();
        for (String w : rawTokens) {
            if (!w.trim().isEmpty() && w.length() >= 2) {
                queryWords.add(w.trim());
            }
        }
        if (queryWords.isEmpty() && normQuery.length() > 0) {
            queryWords.add(normQuery);
        }

        List<ScoredItem> matches = new ArrayList<>();

        for (FaqCacheItem item : faqCache) {
            if (departmentId != null && !Objects.equals(item.departmentId(), departmentId)) {
                continue;
            }

            int matchedWordCount = 0;
            int relevanceScore = 0;

            for (String word : queryWords) {
                boolean inQuestion = item.normQuestion().contains(word);
                boolean inKeywords = item.normKeywords().contains(word);
                boolean inAnswer = item.normAnswer().contains(word);

                if (inQuestion) {
                    matchedWordCount++;
                    relevanceScore += 15; // Từ khóa nằm trong câu hỏi ưu tiên cao nhất
                } else if (inKeywords) {
                    matchedWordCount++;
                    relevanceScore += 10;
                } else if (inAnswer) {
                    matchedWordCount++;
                    relevanceScore += 4;
                }
            }

            // Nếu không có bất kỳ từ khóa nào khớp, bỏ qua
            if (matchedWordCount == 0) {
                continue;
            }

            // Điểm thưởng khi khớp cả cụm từ liên tiếp (Phrase match)
            if (item.normQuestion().contains(normQuery)) {
                relevanceScore += 50;
            } else if (item.normKeywords().contains(normQuery)) {
                relevanceScore += 30;
            } else if (item.normAnswer().contains(normQuery)) {
                relevanceScore += 15;
            }

            // Điểm thưởng nếu tất cả từ khóa tìm kiếm đều có mặt
            if (!queryWords.isEmpty() && matchedWordCount >= queryWords.size()) {
                relevanceScore += 25;
            }

            double confidenceScore = Math.min(1.0, relevanceScore / 100.0);

            matches.add(new ScoredItem(item, relevanceScore, confidenceScore));
        }

        // Sắp xếp theo điểm liên quan giảm dần và lấy tối đa 20 kết quả tốt nhất
        return matches.stream()
                .sorted(Comparator.comparingInt(ScoredItem::score).reversed())
                .limit(20)
                .map(m -> new FaqMatchResult(
                        m.item().id(),
                        m.item().question(),
                        m.item().answer(),
                        m.item().departmentName(),
                        m.item().departmentId(),
                        m.item().category(),
                        m.confidenceScore()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Chuẩn hóa chuỗi tiếng Việt: Bỏ dấu, chuyển chữ thường, xóa ký tự đặc biệt
     */
    public String normalize(String input) {
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
            String category,
            double confidenceScore
    ) {}

    private record FaqCacheItem(
            Long id,
            String question,
            String answer,
            String departmentName,
            Long departmentId,
            String category,
            String normQuestion,
            String normKeywords,
            String normAnswer
    ) {}

    private record ScoredItem(
            FaqCacheItem item,
            int score,
            double confidenceScore
    ) {}
}
