package com.school.counseling.module.auth.service;

import com.school.counseling.common.exception.ResourceNotFoundException;
import com.school.counseling.module.auth.dto.ChangePasswordRequest;
import com.school.counseling.module.auth.dto.UpdateProfileRequest;
import com.school.counseling.module.auth.dto.UserProfileDto;
import com.school.counseling.module.auth.dto.UserPrincipal;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserProfileDto getProfile(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("Mã người dùng không hợp lệ");
        }
        User user = userRepository.findByIdWithRoleAndDepartment(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin tài khoản ID: " + userId));
        return mapToDto(user);
    }

    @Transactional
    public UserProfileDto updateProfile(Long userId, UpdateProfileRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("Mã người dùng không hợp lệ");
        }
        User user = userRepository.findByIdWithRoleAndDepartment(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin tài khoản ID: " + userId));

        user.setFullName(request.getFullName().trim());
        user.setPhone(request.getPhone() != null ? request.getPhone().trim() : null);
        if (request.getAvatarUrl() != null && !request.getAvatarUrl().trim().isEmpty()) {
            user.setAvatarUrl(request.getAvatarUrl().trim());
        }

        User saved = userRepository.save(user);
        log.info("Người dùng ID: {}, username: {} đã cập nhật hồ sơ cá nhân", userId, saved.getUsername());

        // Cập nhật lại SecurityContextHolder để hiển thị ngay tên mới trên giao diện
        refreshSecurityContext(saved);

        return mapToDto(saved);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("Mã người dùng không hợp lệ");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Xác nhận mật khẩu mới không trùng khớp với mật khẩu mới");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin tài khoản ID: " + userId));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu hiện tại không chính xác, vui lòng kiểm tra lại");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mật khẩu mới không được trùng với mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Người dùng ID: {}, username: {} đã đổi mật khẩu thành công", userId, user.getUsername());
    }

    private void refreshSecurityContext(User user) {
        try {
            Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
            if (currentAuth != null) {
                UserPrincipal newPrincipal = UserPrincipal.create(user);
                Authentication newAuth = new UsernamePasswordAuthenticationToken(
                        newPrincipal,
                        currentAuth.getCredentials(),
                        newPrincipal.getAuthorities()
                );
                SecurityContextHolder.getContext().setAuthentication(newAuth);
            }
        } catch (Exception ex) {
            log.warn("Không thể cập nhật SecurityContext sau khi đổi thông tin user: {}", ex.getMessage());
        }
    }

    private UserProfileDto mapToDto(User user) {
        return UserProfileDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .avatarUrl(user.getAvatarUrl())
                .roleName(user.getRole() != null ? user.getRole().getName() : "ROLE_STUDENT")
                .roleDescription(user.getRole() != null ? user.getRole().getDescription() : "Sinh viên")
                .departmentId(user.getDepartment() != null ? user.getDepartment().getId() : null)
                .departmentName(user.getDepartment() != null ? user.getDepartment().getName() : "Không thuộc phòng ban")
                .status(user.getStatus() != null ? user.getStatus() : "ACTIVE")
                .createdAt(user.getCreatedAt())
                .build();
    }
}
