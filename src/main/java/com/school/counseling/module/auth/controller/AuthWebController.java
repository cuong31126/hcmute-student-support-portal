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
    public String loginPage(jakarta.servlet.http.HttpServletRequest request) {
        request.getSession(true);
        org.springframework.security.web.csrf.CsrfToken csrfToken = 
                (org.springframework.security.web.csrf.CsrfToken) request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        return "auth/login";
    }

    @GetMapping("/register")
    public String registerPage(Model model, jakarta.servlet.http.HttpServletRequest request) {
        request.getSession(true);
        org.springframework.security.web.csrf.CsrfToken csrfToken = 
                (org.springframework.security.web.csrf.CsrfToken) request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
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

    private final com.school.counseling.module.auth.service.OtpService otpService;

    @GetMapping("/forgot-password")
    public String forgotPasswordPage(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "step", defaultValue = "1") int step,
            Model model,
            jakarta.servlet.http.HttpServletRequest request) {
        request.getSession(true);
        model.addAttribute("email", email);
        model.addAttribute("step", step);
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password/send-otp")
    public String handleSendOtp(
            @RequestParam("email") String email,
            RedirectAttributes redirectAttributes) {

        if (email == null || email.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Vui lòng nhập địa chỉ email của bạn");
            return "redirect:/auth/forgot-password?step=1";
        }

        boolean sent = otpService.generateAndSendOtpForPasswordReset(email.trim());
        if (!sent) {
            redirectAttributes.addFlashAttribute("errorMessage", "Email không tồn tại trên hệ thống hoặc không thể gửi mã");
            return "redirect:/auth/forgot-password?step=1";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Mã xác thực OTP đã được gửi đến email " + email + ". Vui lòng kiểm tra hộp thư (và thư mục Spam).");
        return "redirect:/auth/forgot-password?step=2&email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @PostMapping("/forgot-password/reset")
    public String handleResetPassword(
            @RequestParam("email") String email,
            @RequestParam("otpCode") String otpCode,
            @RequestParam("newPassword") String newPassword,
            @RequestParam("confirmPassword") String confirmPassword,
            RedirectAttributes redirectAttributes) {

        if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Mật khẩu xác nhận không khớp");
            return "redirect:/auth/forgot-password?step=2&email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
        }

        if (newPassword.length() < 6) {
            redirectAttributes.addFlashAttribute("errorMessage", "Mật khẩu mới phải có ít nhất 6 ký tự");
            return "redirect:/auth/forgot-password?step=2&email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean success = otpService.resetPasswordWithOtp(email.trim(), otpCode.trim(), newPassword);
        if (!success) {
            redirectAttributes.addFlashAttribute("errorMessage", "Mã OTP không chính xác hoặc đã hết hạn (5 phút). Vui lòng thử lại!");
            return "redirect:/auth/forgot-password?step=2&email=" + java.net.URLEncoder.encode(email.trim(), java.nio.charset.StandardCharsets.UTF_8);
        }

        redirectAttributes.addFlashAttribute("successMessage", "Đặt lại mật khẩu thành công! Vui lòng đăng nhập với mật khẩu mới.");
        return "redirect:/auth/login";
    }
}
