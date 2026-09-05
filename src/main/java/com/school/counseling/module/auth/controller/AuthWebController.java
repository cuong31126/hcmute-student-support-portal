package com.school.counseling.module.auth.controller;

import com.school.counseling.module.auth.dto.RegisterRequest;
import com.school.counseling.module.auth.entity.Role;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.RoleRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthWebController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("registerForm", new RegisterRequest());
        return "auth/register";
    }

    @PostMapping("/register")
    public String handleRegister(
            @Valid @ModelAttribute("registerForm") RegisterRequest form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "auth/register";
        }

        if (userRepository.existsByUsername(form.getUsername())) {
            bindingResult.rejectValue("username", "error.user", "Tên đăng nhập đã tồn tại trên hệ thống");
            return "auth/register";
        }

        if (userRepository.existsByEmail(form.getEmail())) {
            bindingResult.rejectValue("email", "error.user", "Email này đã được sử dụng");
            return "auth/register";
        }

        Role studentRole = roleRepository.findByName("ROLE_STUDENT")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_STUDENT").description("Sinh viên").build()));

        User newUser = User.builder()
                .username(form.getUsername())
                .fullName(form.getFullName())
                .email(form.getEmail())
                .passwordHash(passwordEncoder.encode(form.getPassword()))
                .phone(form.getPhone())
                .role(studentRole)
                .status("ACTIVE")
                .build();

        userRepository.save(newUser);
        redirectAttributes.addFlashAttribute("successMessage", "Đăng ký tài khoản thành công! Vui lòng đăng nhập.");
        return "redirect:/auth/login";
    }
}
