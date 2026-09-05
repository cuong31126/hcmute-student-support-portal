package com.school.counseling.module.auth.controller;

import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final DepartmentRepository departmentRepository;

    @GetMapping("/")
    public String index(Model model) {
        List<Department> departments = departmentRepository.findByIsActiveTrue();
        model.addAttribute("departments", departments);
        return "home";
    }
}
