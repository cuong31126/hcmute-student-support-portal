package com.school.counseling.module.ai.controller;

import com.school.counseling.module.ai.entity.Faq;
import com.school.counseling.module.ai.repository.FaqRepository;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class FaqWebController {

    private final FaqRepository faqRepository;
    private final DepartmentRepository departmentRepository;

    @GetMapping("/faqs")
    public String searchFaqs(
            @RequestParam(value = "q", required = false, defaultValue = "") String query,
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {

        int pageSize = 12; // 12 câu hỏi mỗi trang giúp DOM tải siêu nhanh (< 20ms)
        Pageable pageable = PageRequest.of(Math.max(0, page), pageSize, Sort.by(Sort.Direction.DESC, "viewCount", "createdAt"));

        Page<Faq> faqsPage;
        if (query != null && !query.trim().isEmpty()) {
            List<Faq> searchResults = faqRepository.searchFaqs(query.trim());
            int start = Math.min((int) pageable.getOffset(), searchResults.size());
            int end = Math.min((start + pageable.getPageSize()), searchResults.size());
            faqsPage = new org.springframework.data.domain.PageImpl<>(
                    searchResults.subList(start, end),
                    pageable,
                    searchResults.size()
            );
        } else if (departmentId != null) {
            faqsPage = faqRepository.findByDepartmentIdAndIsActiveTrue(departmentId, pageable);
        } else {
            faqsPage = faqRepository.findByIsActiveTrue(pageable);
        }

        List<Department> departments = departmentRepository.findByIsActiveTrue();

        model.addAttribute("faqsPage", faqsPage);
        model.addAttribute("departments", departments);
        model.addAttribute("selectedDept", departmentId);
        model.addAttribute("query", query);

        return "faq/search";
    }
}
