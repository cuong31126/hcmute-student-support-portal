package com.school.counseling.module.auth.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.dto.ChangePasswordRequest;
import com.school.counseling.module.auth.dto.UpdateProfileRequest;
import com.school.counseling.module.auth.dto.UserProfileDto;
import com.school.counseling.module.auth.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/profile")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ProfileRestController {

    private final ProfileService profileService;

    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileDto>> getMyProfile() {
        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalStateException("Người dùng chưa đăng nhập"));
        UserProfileDto profile = profileService.getProfile(currentUserId);
        return ResponseEntity.ok(ApiResponse.success(profile, "Lấy thông tin tài khoản thành công"));
    }

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<UserProfileDto>> updateMyProfile(@Valid @RequestBody UpdateProfileRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalStateException("Người dùng chưa đăng nhập"));
        UserProfileDto updated = profileService.updateProfile(currentUserId, request);
        return ResponseEntity.ok(ApiResponse.success(updated, "Cập nhật hồ sơ tài khoản thành công!"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changeMyPassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalStateException("Người dùng chưa đăng nhập"));
        try {
            profileService.changePassword(currentUserId, request);
            return ResponseEntity.ok(ApiResponse.success(null, "Đổi mật khẩu thành công!"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
        }
    }
}
