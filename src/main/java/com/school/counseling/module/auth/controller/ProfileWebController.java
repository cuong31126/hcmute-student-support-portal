package com.school.counseling.module.auth.controller;

import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.dto.ChangePasswordRequest;
import com.school.counseling.module.auth.dto.UpdateProfileRequest;
import com.school.counseling.module.auth.dto.UserProfileDto;
import com.school.counseling.module.auth.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequestMapping("/profile")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ProfileWebController {

    private final ProfileService profileService;

    @GetMapping
    public String profilePage(Model model) {
        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalStateException("Người dùng chưa đăng nhập"));

        UserProfileDto profile = profileService.getProfile(currentUserId);
        model.addAttribute("userProfile", profile);
        model.addAttribute("updateProfileForm", UpdateProfileRequest.builder()
                .fullName(profile.getFullName())
                .phone(profile.getPhone())
                .avatarUrl(profile.getAvatarUrl())
                .build());
        model.addAttribute("changePasswordForm", new ChangePasswordRequest());

        return "auth/profile";
    }

    @PostMapping("/update")
    public String handleUpdateProfile(
            @Valid @ModelAttribute("updateProfileForm") UpdateProfileRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalStateException("Người dùng chưa đăng nhập"));

        if (bindingResult.hasErrors()) {
            UserProfileDto profile = profileService.getProfile(currentUserId);
            model.addAttribute("userProfile", profile);
            model.addAttribute("changePasswordForm", new ChangePasswordRequest());
            return "auth/profile";
        }

        try {
            profileService.updateProfile(currentUserId, form);
            redirectAttributes.addFlashAttribute("successMessage", "Cập nhật hồ sơ tài khoản thành công!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lỗi: " + ex.getMessage());
        }

        return "redirect:/profile";
    }

    @PostMapping("/change-password")
    public String handleChangePassword(
            @Valid @ModelAttribute("changePasswordForm") ChangePasswordRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalStateException("Người dùng chưa đăng nhập"));

        if (bindingResult.hasErrors()) {
            UserProfileDto profile = profileService.getProfile(currentUserId);
            model.addAttribute("userProfile", profile);
            model.addAttribute("updateProfileForm", UpdateProfileRequest.builder()
                    .fullName(profile.getFullName())
                    .phone(profile.getPhone())
                    .avatarUrl(profile.getAvatarUrl())
                    .build());
            return "auth/profile";
        }

        try {
            profileService.changePassword(currentUserId, form);
            redirectAttributes.addFlashAttribute("successMessage", "Đổi mật khẩu tài khoản thành công! Vui lòng ghi nhớ mật khẩu mới.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Không thể đổi mật khẩu: " + ex.getMessage());
        }

        return "redirect:/profile";
    }
}
