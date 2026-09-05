package com.school.counseling.module.ai.controller;

import com.school.counseling.module.ai.entity.Faq;
import com.school.counseling.module.ai.repository.FaqRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class FaqWebController {

    private final FaqRepository faqRepository;

    @GetMapping("/faqs")
    public String searchFaqs(@RequestParam(value = "q", required = false, defaultValue = "") String query, Model model) {
        List<Faq> faqs;
        if (query != null && !query.trim().isEmpty()) {
            faqs = faqRepository.searchFaqs(query.trim());
        } else {
            faqs = faqRepository.findAll().stream().limit(30).toList();
        }
        model.addAttribute("faqs", faqs);
        model.addAttribute("query", query);
        return "faq/search";
    }
}
